"""
Ablation study: which parts of the pipeline actually earn their place?

A reviewer's first question about a multi-component system is whether every
component contributes, or whether one of them carries the result and the rest
are decoration. This answers that with measurements rather than argument.

Three axes, each holding everything else fixed:

  features  ECFP4 alone / descriptors alone / both
            Does the descriptor block add anything over the fingerprint?

  split     random / scaffold / time
            Quantifies how optimistic each evaluation protocol is. Random
            splits are still common in QSAR papers; this shows what they cost.

  labelling three-state (masked grey zone) vs two-state (everything forced)
            Whether masking the ambiguous 1-10 uM band is worth the data it
            discards.

  scramble  real labels vs permuted labels (y-scrambling)
            The negative control. An OECD validation principle: if performance
            does not collapse when the labels are shuffled, the model was
            exploiting dataset structure rather than chemistry.

Every configuration retrains the activity head on the same molecules, so the
differences are attributable to the change and nothing else.

    python scripts/ablate.py                 # all axes
    python scripts/ablate.py --axis features
"""
from __future__ import annotations

import argparse
import gc
import json
import logging
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob.config import (
    ARTIFACT_DIR,
    CLASS_KEYS,
    FP_BITS,
    LIABILITY_CLASS_KEYS,
    RANDOM_SEED,
    TEST_FRACTION,
    VALID_FRACTION,
)
from chemrob.dataset import load_dataset
from chemrob.evaluate import evaluate_multitask, summarize
from chemrob.keepawake import keep_awake
from chemrob.models.baseline import BaselineMultiTask
from chemrob.scaffold import scaffold_split
from chemrob.validation import time_split

log = logging.getLogger("ablate")
OUT = ARTIFACT_DIR / "ablation.json"

# Feature slices. The matrix is [ECFP4 counts | physicochemical descriptors].
FEATURE_SETS = {
    "ecfp4_only": slice(0, FP_BITS),
    "descriptors_only": slice(FP_BITS, None),
    "ecfp4_plus_descriptors": slice(None),
}


def _therapeutic(metrics: pd.DataFrame) -> pd.DataFrame:
    """Liabilities are excluded: their base rates are so high that including
    them inflates macro AUPRC and hides what the therapeutic heads are doing."""
    return metrics[~metrics["task"].isin(LIABILITY_CLASS_KEYS)]


def _fit_score(ds, tr, va, te, cols=slice(None), *, label: str,
               kind: str = "hgb") -> dict:
    t0 = time.time()
    # ds.X[tr][:, cols] would materialise the whole 1.7 GB row slice and only
    # then drop columns, so a feature subset costs more memory than using every
    # feature. Combining both indexers in one step builds just the block wanted;
    # the earlier version was killed mid-run by exactly this.
    X_train = np.ascontiguousarray(ds.X[tr, cols])
    X_valid = np.ascontiguousarray(ds.X[va, cols])
    X_test = np.ascontiguousarray(ds.X[te, cols])
    try:
        model = BaselineMultiTask(kind=kind).fit(
            X_train, ds.Y_class[tr], list(CLASS_KEYS),
            X_valid=X_valid, Y_valid=ds.Y_class[va],
        )
        prob = model.predict_matrix(X_test)
        m = evaluate_multitask(ds.Y_class[te], prob, list(CLASS_KEYS))
        s = summarize(_therapeutic(m))
        s["config"] = label
        s["n_features"] = int(X_train.shape[1])
        s["n_train"] = int(len(tr))
        s["n_test"] = int(len(te))
        s["seconds"] = round(time.time() - t0, 1)
        log.info("%-26s AUROC %.4f  AUPRC %.4f  (%d features, %.0fs)",
                 label, s.get("macro_auroc", 0), s.get("macro_auprc", 0),
                 s["n_features"], s["seconds"])
        return s
    finally:
        del X_train, X_valid, X_test
        gc.collect()


def ablate_features(ds) -> list[dict]:
    log.info("=== features ===")
    tr, va, te = ds.train_idx, ds.valid_idx, ds.test_idx
    return [_fit_score(ds, tr, va, te, cols, label=name)
            for name, cols in FEATURE_SETS.items()]


def ablate_splits(ds) -> list[dict]:
    """
    Random vs scaffold vs time, on identical data.

    Random is included precisely because it is the wrong protocol: close
    analogues land on both sides, and the gap to the scaffold split is the
    inflation a random-split paper would be reporting.
    """
    log.info("=== split protocol ===")
    rows: list[dict] = []
    n = len(ds.smiles)

    rng = np.random.default_rng(RANDOM_SEED)
    perm = rng.permutation(n)
    n_te = int(TEST_FRACTION * n)
    n_va = int(VALID_FRACTION * n)
    rows.append(_fit_score(ds, perm[n_te + n_va:], perm[n_te:n_te + n_va],
                           perm[:n_te], label="split:random"))

    tr, va, te = scaffold_split(ds.smiles, 1.0 - VALID_FRACTION - TEST_FRACTION,
                               VALID_FRACTION, seed=RANDOM_SEED)
    rows.append(_fit_score(ds, np.asarray(tr), np.asarray(va), np.asarray(te),
                           label="split:scaffold"))

    if "first_year" in ds.frame.columns:
        t_tr, t_va, t_te = time_split(ds, 2018)
        if len(t_te) >= 200:
            rows.append(_fit_score(ds, t_tr, t_va, t_te, label="split:time_2018"))
    return rows


def ablate_labelling(ds) -> list[dict]:
    """
    Three-state labelling (grey zone masked) against forcing every measurement
    into a class at the active threshold.
    """
    log.info("=== labelling ===")
    tr, va, te = ds.train_idx, ds.valid_idx, ds.test_idx
    rows = [_fit_score(ds, tr, va, te, label="labels:three_state_masked")]

    # Rebuild labels with no masked band: anything measured becomes 0 or 1.
    from chemrob.config import CLASS_BY_KEY

    forced = ds.Y_class.copy()
    pmax = ds.frame[[f"pmax_{k}" for k in CLASS_KEYS]].to_numpy(dtype=np.float32)
    for j, key in enumerate(CLASS_KEYS):
        thr = CLASS_BY_KEY[key].active_threshold
        measured = ~np.isnan(pmax[:, j])
        forced[measured, j] = (pmax[measured, j] >= thr).astype(np.float32)

    original = ds.Y_class
    try:
        ds.Y_class = forced
        rows.append(_fit_score(ds, tr, va, te, label="labels:two_state_forced"))
    finally:
        ds.Y_class = original
    return rows


def ablate_estimator(ds) -> list[dict]:
    """
    The same features, split and labels through three estimator families.

    Every other number in this project is absolute and therefore hard for a
    reader to place. A random forest on ECFP4 is the reference most people in
    the field already have a feel for, and logistic regression bounds how much
    of the result is simply linear separability of the fingerprint.
    """
    log.info("=== estimator family ===")
    tr, va, te = ds.train_idx, ds.valid_idx, ds.test_idx
    rows = []
    for kind, label in (("hgb", "estimator:gradient_boosting"),
                        ("rf", "estimator:random_forest"),
                        ("logreg", "estimator:logistic_regression")):
        rows.append(_fit_score(ds, tr, va, te, label=label, kind=kind))
    return rows


def ablate_scrambling(ds, n_perm: int = 3) -> list[dict]:
    """
    y-scrambling: the negative control.

    Labels are permuted *within each task and only among labelled entries*, so
    the base rate and the missingness pattern are preserved exactly and the only
    thing destroyed is the structure-activity relationship. Shuffling across the
    whole matrix instead would change how many positives each task has, and the
    resulting collapse would be partly an artefact of that.

    A model that still performs after this is reading dataset structure, not
    chemistry.
    """
    log.info("=== y-scrambling negative control ===")
    tr, va, te = ds.train_idx, ds.valid_idx, ds.test_idx
    rows = [_fit_score(ds, tr, va, te, label="scramble:real_labels")]

    original = ds.Y_class
    rng = np.random.default_rng(RANDOM_SEED)
    try:
        for i in range(n_perm):
            permuted = original.copy()
            for j in range(permuted.shape[1]):
                col = permuted[:, j]
                labelled = ~np.isnan(col)
                vals = col[labelled]
                rng.shuffle(vals)
                col[labelled] = vals
            ds.Y_class = permuted
            rows.append(_fit_score(ds, tr, va, te,
                                   label=f"scramble:permuted_{i + 1}"))
    finally:
        ds.Y_class = original

    perm_rows = [r for r in rows if r["config"].startswith("scramble:permuted")]
    if perm_rows:
        vals = [r["macro_auprc"] for r in perm_rows]
        real = rows[0]["macro_auprc"]
        log.info("real %.4f vs permuted %.4f +/- %.4f  (gap %.4f)",
                 real, float(np.mean(vals)), float(np.std(vals)),
                 real - float(np.mean(vals)))
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--axis",
                    choices=["features", "splits", "labelling", "scramble",
                             "estimator", "all"],
                    default="all")
    ap.add_argument("--permutations", type=int, default=3,
                    help="label permutations for the y-scrambling control")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%H:%M:%S")

    ds = load_dataset()
    # Load what is already there. Running a single axis used to overwrite the
    # file with only that axis, silently discarding the others - the same
    # "artifacts disagree with each other" failure this project has already been
    # bitten by once.
    results: dict[str, list[dict]] = {}
    if OUT.exists():
        try:
            results = json.loads(OUT.read_text(encoding="utf-8"))
            log.info("merging into existing results: %s", ", ".join(results))
        except Exception:  # noqa: BLE001
            log.warning("could not read %s; starting fresh", OUT)

    def _checkpoint() -> None:
        """Persist after every axis: these runs take an hour and a crash in the
        last one should not discard the ones that already succeeded."""
        OUT.write_text(json.dumps(results, indent=2, default=float), encoding="utf-8")

    if args.axis in ("features", "all"):
        results["features"] = ablate_features(ds)
        _checkpoint()
    if args.axis in ("splits", "all"):
        results["splits"] = ablate_splits(ds)
        _checkpoint()
    if args.axis in ("labelling", "all"):
        results["labelling"] = ablate_labelling(ds)
        _checkpoint()
    if args.axis in ("estimator", "all"):
        results["estimator"] = ablate_estimator(ds)
        _checkpoint()
    if args.axis in ("scramble", "all"):
        results["scramble"] = ablate_scrambling(ds, args.permutations)
        _checkpoint()

    print("\n" + "=" * 88)
    print("ABLATION - therapeutic classes only, macro over 14 heads")
    print("=" * 88)
    for axis, rows in results.items():
        df = pd.DataFrame(rows)
        cols = [c for c in ("config", "n_features", "n_train", "n_test",
                            "macro_auroc", "macro_auprc", "macro_f1", "seconds")
                if c in df.columns]
        print(f"\n[{axis}]")
        print(df[cols].to_string(index=False))
        if len(df) > 1 and "macro_auprc" in df.columns:
            best = df.loc[df["macro_auprc"].idxmax(), "config"]
            spread = df["macro_auprc"].max() - df["macro_auprc"].min()
            print(f"  best: {best}   spread across this axis: {spread:.4f} AUPRC")

    OUT.write_text(json.dumps(results, indent=2, default=float), encoding="utf-8")
    print(f"\nWritten to {OUT}")
    return 0


if __name__ == "__main__":
    # Windows counts a long fit with no keyboard input as idle and will
    # suspend underneath it; that is what killed a seed run mid-way.
    with keep_awake("the ablation sweep"):
        raise SystemExit(main())
