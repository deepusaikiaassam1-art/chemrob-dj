"""
External validation on BindingDB.

Every other number in this project comes from a held-out slice of the project's
own curated dataset. This one does not. BindingDB is a separate database with
its own curators, its own literature coverage and its own assay conventions,
and the released bundle has never seen any of it - it is opt-in and switched
off, precisely because including it did not help.

That makes it usable as an external test set, subject to one strict condition.
BindingDB re-aggregates ChEMBL, so any compound already in training must be
removed or this measures memorisation. Exclusion is by standardised SMILES
against the applicability-domain index, which is exactly the set of molecules
the model was fitted on.

Labels are rebuilt from BindingDB potencies using the same per-class thresholds
as the training data, so "active" means the same thing on both sides.

    python scripts/external_validation.py
    python scripts/external_validation.py --min-per-class 50
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob import bindingdb
from chemrob.applicability import ApplicabilityDomain
from chemrob.config import (
    ARTIFACT_DIR, CLASS_BY_KEY, CLASS_KEYS, TARGET_TO_CLASS,
)
from chemrob.evaluate import evaluate_multitask, summarize, summarize_by_block
from chemrob.featurize import featurize
from chemrob.keepawake import keep_awake
from chemrob.models.baseline import BaselineMultiTask
from chemrob.standardize import standardize_smiles
from chemrob.train import ART

log = logging.getLogger("external")
OUT = ARTIFACT_DIR / "external_validation.json"


def build_external_set(records: pd.DataFrame, training: set[str],
                       *, min_per_class: int = 30):
    """
    Turn BindingDB rows into (smiles, label matrix), excluding anything seen.

    Aggregation mirrors the training pipeline: median pActivity per
    molecule-target pair, then a class is active if any of its targets is active
    and inactive only if measured and none reached the threshold.
    """
    df = records.copy()
    df["p_activity"] = 9.0 - np.log10(df["standard_value"].astype(float))
    df = df[df["p_activity"].between(2.0, 12.0)]

    log.info("standardising %d unique structures ...", df["canonical_smiles"].nunique())
    mapping: dict[str, str | None] = {}
    for smi in df["canonical_smiles"].unique():
        res = standardize_smiles(str(smi))
        mapping[smi] = res.smiles if res.ok else None
    df["std_smiles"] = df["canonical_smiles"].map(mapping)
    df = df[df["std_smiles"].notna()]

    seen = df["std_smiles"].isin(training)
    log.info("excluding %d of %d rows whose molecule is in the training set "
             "(%d rows remain)", int(seen.sum()), len(df), int((~seen).sum()))
    df = df[~seen]
    if df.empty:
        return [], np.zeros((0, len(CLASS_KEYS)), dtype=np.float32), {}

    # Censored '>' bounds can support inactivity but never activity, same rule
    # as the training curation.
    df["censored"] = df["standard_relation"].isin([">", ">="])
    agg = (df.groupby(["std_smiles", "target_chembl_id"], as_index=False)
             .agg(p_activity=("p_activity", "median"),
                  censored=("censored", "mean")))
    agg["class_key"] = agg["target_chembl_id"].map(TARGET_TO_CLASS)
    agg = agg[agg["class_key"].notna()]

    molecules = sorted(agg["std_smiles"].unique())
    index = {s: i for i, s in enumerate(molecules)}
    Y = np.full((len(molecules), len(CLASS_KEYS)), np.nan, dtype=np.float32)
    tested = np.zeros_like(Y, dtype=bool)

    for smi, key, p, cens in zip(agg["std_smiles"], agg["class_key"],
                                 agg["p_activity"], agg["censored"]):
        j = CLASS_KEYS.index(key)
        i = index[smi]
        cls = CLASS_BY_KEY[key]
        tested[i, j] = True
        if p >= cls.active_threshold and cens <= 0.5:
            Y[i, j] = 1.0
        elif p < cls.inactive_threshold:
            Y[i, j] = max(Y[i, j], 0.0) if not np.isnan(Y[i, j]) else 0.0
        # a measurement between the thresholds leaves the cell masked
    # a class stays active once any target reached it
    for j in range(Y.shape[1]):
        col, t = Y[:, j], tested[:, j]
        col[t & np.isnan(col)] = np.nan

    counts = {}
    for j, key in enumerate(CLASS_KEYS):
        lab = Y[:, j]
        n = int((~np.isnan(lab)).sum())
        pos = int(np.nansum(lab == 1))
        counts[key] = {"labelled": n, "positive": pos}
        if n < min_per_class or pos < 5 or pos == n:
            Y[:, j] = np.nan          # too thin to evaluate honestly
    return molecules, Y, counts


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-per-class", type=int, default=30)
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%H:%M:%S")

    log.info("loading BindingDB (external, never trained on) ...")
    records = bindingdb.fetch()
    if records.empty:
        print("No BindingDB records available.", file=sys.stderr)
        return 1

    ad = ApplicabilityDomain.load(ART["ad"])
    training = set(ad.smiles)
    log.info("training set holds %d molecules", len(training))

    molecules, Y, counts = build_external_set(records, training,
                                              min_per_class=args.min_per_class)
    if not molecules:
        print("Nothing left after excluding training molecules.", file=sys.stderr)
        return 1

    evaluable = [k for k in CLASS_KEYS
                 if not np.isnan(Y[:, CLASS_KEYS.index(k)]).all()]
    log.info("external set: %d molecules, %d evaluable classes",
             len(molecules), len(evaluable))

    log.info("featurising and scoring with the released bundle ...")
    from rdkit import Chem, RDLogger

    RDLogger.DisableLog("rdApp.*")
    mols = [Chem.MolFromSmiles(s) for s in molecules]
    keep = [i for i, mo in enumerate(mols) if mo is not None]
    if len(keep) != len(molecules):
        log.info("dropping %d structures that failed to re-parse",
                 len(molecules) - len(keep))
        molecules = [molecules[i] for i in keep]
        Y = Y[keep]
        mols = [mols[i] for i in keep]
    X = np.vstack([featurize(mo) for mo in mols])
    model = BaselineMultiTask.load(ART["activity"])
    P = model.predict_matrix(X)

    m = evaluate_multitask(Y, P, list(CLASS_KEYS))
    internal = json.loads((ARTIFACT_DIR / "metrics.json").read_text(encoding="utf-8"))
    internal_by_task = {r["task"]: r for r in internal["activity"]["per_task"]}

    rows = []
    for r in m.to_dict(orient="records"):
        if r.get("auroc") is None:
            continue
        ref = internal_by_task.get(r["task"], {})
        rows.append({
            **r,
            "internal_auroc": ref.get("auroc"),
            "internal_auprc": ref.get("auprc"),
            "delta_auprc": (round(r["auprc"] - ref["auprc"], 4)
                            if ref.get("auprc") else None),
        })

    results = {
        "source": "BindingDB 202609 (Articles + PDSPKi) - excluded from training",
        "n_molecules": len(molecules),
        "n_evaluable_classes": len(rows),
        "label_counts": counts,
        "summary": summarize(m),
        "summary_by_block": summarize_by_block(m),
        "per_task": rows,
    }
    OUT.write_text(json.dumps(results, indent=2, default=float), encoding="utf-8")

    print("\n" + "=" * 86)
    print("EXTERNAL VALIDATION - BindingDB, never seen by the released model")
    print("=" * 86)
    print(f"{len(molecules):,} molecules, none present in the training set; "
          f"{len(rows)} classes with enough labels to evaluate")
    df = pd.DataFrame(rows)
    cols = ["task", "n", "n_positive", "positive_rate", "auroc", "auprc",
            "auprc_lift", "mcc", "internal_auprc", "delta_auprc"]
    print()
    print(df[[c for c in cols if c in df.columns]].to_string(index=False))
    print()
    print("external :", json.dumps(results["summary"]))
    print(f"\nWritten to {OUT}")
    return 0


if __name__ == "__main__":
    # Windows counts a long fit with no keyboard input as idle and will
    # suspend underneath it; that is what killed a seed run mid-way.
    with keep_awake("external validation"):
        raise SystemExit(main())
