"""
Post-hoc diagnostics: calibration and applicability domain.

Both modules exist in the codebase and neither has ever been *reported*, which
means two load-bearing claims currently rest on no evidence:

  calibration  models/baseline.py fits a sigmoid on a held-out fold, the README
               says probabilities are calibrated, and optimize.py weights the
               probability delta at 1.0 against 0.15 for potency explicitly
               because it is calibrated. If it is not, the optimizer's ranking
               is built on sand.

  applicability
               The AD check emits in_domain / borderline / out_of_domain and
               nothing shows the verdict predicts anything. Until performance is
               shown to decline across those bands, it is a feature description
               rather than a result.

Everything here reuses the saved bundle and the existing test split. No model is
retrained, so the numbers describe exactly the bundle in artifacts/.

    python scripts/diagnostics.py                  # both
    python scripts/diagnostics.py --only calibration
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

from chemrob.applicability import ApplicabilityDomain
from chemrob.config import AD_BORDERLINE, AD_IN_DOMAIN, ARTIFACT_DIR, CLASS_KEYS
from chemrob.dataset import load_dataset
from chemrob.evaluate import evaluate_multitask, summarize
from chemrob.keepawake import keep_awake
from chemrob.models.baseline import BaselineMultiTask
from chemrob.train import ART

log = logging.getLogger("diagnostics")
OUT = ARTIFACT_DIR / "diagnostics.json"


# --------------------------------------------------------------------------
# G3 - calibration
# --------------------------------------------------------------------------
def _ece(y: np.ndarray, p: np.ndarray, n_bins: int = 10) -> tuple[float, list[dict]]:
    """
    Expected calibration error, plus the reliability curve it is computed from.

    Equal-width bins over the probability range. The curve is returned because
    a single ECE number hides the direction of the miscalibration, and
    over-confidence at the top of the range is what actually breaks a ranking.
    """
    edges = np.linspace(0.0, 1.0, n_bins + 1)
    ece, curve = 0.0, []
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (p >= lo) & (p < hi if hi < 1.0 else p <= hi)
        if not m.any():
            continue
        conf, acc, frac = float(p[m].mean()), float(y[m].mean()), float(m.mean())
        ece += frac * abs(acc - conf)
        curve.append({
            "bin_lower": round(float(lo), 2), "bin_upper": round(float(hi), 2),
            "n": int(m.sum()), "mean_predicted": round(conf, 4),
            "observed_rate": round(acc, 4), "gap": round(acc - conf, 4),
        })
    return ece, curve


def calibration_report(Y: np.ndarray, P: np.ndarray, tasks: list[str]) -> dict:
    from sklearn.metrics import brier_score_loss

    rows, curves = [], {}
    for j, task in enumerate(tasks):
        keep = ~np.isnan(Y[:, j]) & ~np.isnan(P[:, j])
        if keep.sum() < 100 or len(np.unique(Y[keep, j])) < 2:
            continue
        y, p = Y[keep, j].astype(int), P[keep, j].astype(float)
        ece, curve = _ece(y, p)
        brier = float(brier_score_loss(y, p))
        base = float(y.mean())
        rows.append({
            "task": task, "n": int(keep.sum()), "base_rate": round(base, 4),
            "brier": round(brier, 4),
            # Brier alone is unreadable without a reference: a task with a 5%
            # base rate scores well by predicting 0.05 everywhere. The skill
            # score compares against exactly that constant baseline.
            "brier_skill_vs_base": round(1 - brier / max(base * (1 - base), 1e-9), 4),
            "ece": round(ece, 4),
            "mean_predicted": round(float(p.mean()), 4),
            "observed_rate": round(base, 4),
        })
        curves[task] = curve

    df = pd.DataFrame(rows)
    return {
        "per_task": rows,
        "reliability_curves": curves,
        "summary": {
            "n_tasks": int(len(df)),
            "mean_brier": round(float(df["brier"].mean()), 4) if len(df) else None,
            "mean_ece": round(float(df["ece"].mean()), 4) if len(df) else None,
            "worst_ece_task": df.loc[df["ece"].idxmax(), "task"] if len(df) else None,
            "worst_ece": round(float(df["ece"].max()), 4) if len(df) else None,
        },
    }


# --------------------------------------------------------------------------
# G4 - applicability domain
# --------------------------------------------------------------------------
def ad_report(ds, P: np.ndarray, test_idx: np.ndarray, *,
              sample: int = 6000) -> dict:
    """
    Does the domain verdict predict unreliability?

    Every test molecule is compared against the whole training set, which is
    quadratic, so a random sample is scored rather than all of them. The result
    wanted is monotone: accuracy falling as similarity to the training set
    falls. If it is flat, the AD module is not doing its job and that needs
    saying before a referee says it.
    """
    from rdkit import Chem, DataStructs, RDLogger

    from chemrob.featurize import ecfp4_bitvect

    RDLogger.DisableLog("rdApp.*")
    ad = ApplicabilityDomain.load(ART["ad"])
    log.info("AD index holds %d training molecules", len(ad.fps))

    rng = np.random.default_rng(0)
    pos = np.arange(len(test_idx))
    if len(pos) > sample:
        pos = np.sort(rng.choice(pos, size=sample, replace=False))
    log.info("scoring %d test molecules against the training set ...", len(pos))

    sims = np.full(len(pos), np.nan, dtype=np.float32)
    for k, i in enumerate(pos):
        if k and k % 1000 == 0:
            log.info("  %d / %d", k, len(pos))
        mol = Chem.MolFromSmiles(ds.smiles[test_idx[i]])
        if mol is None:
            continue
        s = DataStructs.BulkTanimotoSimilarity(ecfp4_bitvect(mol), ad.fps)
        sims[k] = max(s) if s else 0.0

    Y = ds.Y_class[test_idx][pos]
    Pv = P[pos]

    def _block(mask: np.ndarray, label: str) -> dict | None:
        if mask.sum() < 50:
            return None
        m = evaluate_multitask(Y[mask], Pv[mask], list(CLASS_KEYS))
        s = summarize(m)
        s["band"] = label
        s["n_molecules"] = int(mask.sum())
        s["mean_max_similarity"] = round(float(np.nanmean(sims[mask])), 4)
        return s

    verdicts = []
    for label, mask in (
        ("in_domain", sims >= AD_IN_DOMAIN),
        ("borderline", (sims >= AD_BORDERLINE) & (sims < AD_IN_DOMAIN)),
        ("out_of_domain", sims < AD_BORDERLINE),
    ):
        b = _block(mask, label)
        if b:
            verdicts.append(b)

    bands = []
    edges = [0.0, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 1.01]
    for lo, hi in zip(edges[:-1], edges[1:]):
        b = _block((sims >= lo) & (sims < hi), f"{lo:.1f}-{hi:.1f}")
        if b:
            bands.append(b)

    return {
        "n_scored": int(len(pos)),
        "similarity_distribution": {
            "mean": round(float(np.nanmean(sims)), 4),
            "median": round(float(np.nanmedian(sims)), 4),
            **{f"p{q}": round(float(np.nanpercentile(sims, q)), 4)
               for q in (5, 25, 50, 75, 95)},
        },
        "by_verdict": verdicts,
        "by_similarity_band": bands,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["calibration", "ad"], default=None)
    ap.add_argument("--ad-sample", type=int, default=6000)
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%H:%M:%S")

    # Seed from whatever is already on disk. Both branches below write OUT, so
    # starting from an empty dict meant `--only ad` silently erased the
    # calibration block written by an earlier `--only calibration` run - and the
    # README went on quoting an ECE that no artifact contained. Same failure as
    # ablate.py had; fixed the same way.
    results: dict = {}
    if OUT.exists():
        try:
            results = json.loads(OUT.read_text(encoding="utf-8"))
            if results:
                log.info("merging into existing diagnostics: %s", ", ".join(results))
        except Exception:  # noqa: BLE001
            log.warning("could not read %s; starting fresh", OUT)

    ds = load_dataset()
    te = ds.test_idx
    model = BaselineMultiTask.load(ART["activity"])
    log.info("scoring the held-out split with the saved bundle ...")
    P = model.predict_matrix(np.ascontiguousarray(ds.X[te]))
    Y = ds.Y_class[te]

    if args.only in (None, "calibration"):
        results["calibration"] = calibration_report(Y, P, list(CLASS_KEYS))
        OUT.write_text(json.dumps(results, indent=2, default=float), encoding="utf-8")
        c = results["calibration"]
        print("\n=== CALIBRATION (held-out scaffold split) ===")
        df = pd.DataFrame(c["per_task"])
        print(df[["task", "n", "base_rate", "mean_predicted", "observed_rate",
                  "brier", "brier_skill_vs_base", "ece"]].to_string(index=False))
        print(f"\nmean Brier {c['summary']['mean_brier']}   mean ECE "
              f"{c['summary']['mean_ece']}   worst: {c['summary']['worst_ece_task']} "
              f"({c['summary']['worst_ece']})")

    if args.only in (None, "ad"):
        results["applicability_domain"] = ad_report(ds, P, te, sample=args.ad_sample)
        OUT.write_text(json.dumps(results, indent=2, default=float), encoding="utf-8")
        a = results["applicability_domain"]
        print(f"\n=== APPLICABILITY DOMAIN ({a['n_scored']} test molecules) ===")
        print("max Tanimoto to training set:",
              json.dumps(a["similarity_distribution"]))
        print("\nBy verdict:")
        for b in a["by_verdict"]:
            print(f"  {b['band']:<16}n={b['n_molecules']:>6}  meanSim {b['mean_max_similarity']:.3f}"
                  f"  AUROC {b.get('macro_auroc', float('nan')):.4f}"
                  f"  AUPRC {b.get('macro_auprc', float('nan')):.4f}")
        print("\nBy similarity band:")
        for b in a["by_similarity_band"]:
            print(f"  {b['band']:<16}n={b['n_molecules']:>6}"
                  f"  AUROC {b.get('macro_auroc', float('nan')):.4f}"
                  f"  AUPRC {b.get('macro_auprc', float('nan')):.4f}")

    print(f"\nWritten to {OUT}")
    return 0


if __name__ == "__main__":
    # Windows counts a long fit with no keyboard input as idle and will
    # suspend underneath it; that is what killed a seed run mid-way.
    with keep_awake("the diagnostics run"):
        raise SystemExit(main())
