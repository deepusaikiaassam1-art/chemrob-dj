"""
Compare two trained bundles on ONE common test set.

Metrics from two training runs are not comparable when each was scored on its
own split: a scaffold split recomputed over a different molecule population
produces a different, differently-hard test set, so the numbers move for reasons
that have nothing to do with model quality.

This script removes that confound. It scores both bundles on the same molecules,
restricted to molecules neither bundle was trained on, so the comparison
measures the models rather than their splits.

    python scripts/compare_versions.py --old artifacts_v1_backup --new artifacts
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
from chemrob.config import CLASS_KEYS
from chemrob.dataset import load_dataset
from chemrob.evaluate import evaluate_multitask, summarize
from chemrob.models.baseline import BaselineMultiTask


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", default="artifacts_v1_backup")
    ap.add_argument("--new", default="artifacts")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%H:%M:%S")
    log = logging.getLogger("compare")

    old_dir, new_dir = Path(args.old), Path(args.new)
    old = BaselineMultiTask.load(old_dir / "baseline_activity.joblib")
    new = BaselineMultiTask.load(new_dir / "baseline_activity.joblib")

    ds = load_dataset()
    test = ds.test_idx

    # Exclude anything either model saw in training. The new model's own test
    # split is already unseen for it, but some of those molecules were in the
    # old model's training set and would flatter it badly.
    old_ad = ApplicabilityDomain.load(old_dir / "applicability.joblib")
    old_train = set(old_ad.smiles)
    new_ad = ApplicabilityDomain.load(new_dir / "applicability.joblib")
    new_train = set(new_ad.smiles)

    keep = np.array([
        ds.smiles[i] not in old_train and ds.smiles[i] not in new_train for i in test
    ])
    common = test[keep]
    log.info("common clean test set: %d molecules (%d dropped as seen by one of the models)",
             len(common), int((~keep).sum()))
    if len(common) < 500:
        log.error("too few molecules unseen by both models to compare fairly")
        return 1

    X = np.ascontiguousarray(ds.X[common])
    Y = ds.Y_class[common]

    P_old = old.predict_matrix(X)
    P_new = new.predict_matrix(X)
    m_old = evaluate_multitask(Y, P_old, list(CLASS_KEYS))
    m_new = evaluate_multitask(Y, P_new, list(CLASS_KEYS))

    merged = (
        m_old[["task", "n", "n_positive", "auroc", "auprc"]]
        .rename(columns={"auroc": "auroc_old", "auprc": "auprc_old"})
        .merge(
            m_new[["task", "auroc", "auprc"]]
            .rename(columns={"auroc": "auroc_new", "auprc": "auprc_new"}),
            on="task",
        )
    )
    merged["d_auprc"] = (merged["auprc_new"] - merged["auprc_old"]).round(4)
    merged["d_auroc"] = (merged["auroc_new"] - merged["auroc_old"]).round(4)

    print(f"\n=== Both models on the SAME {len(common)} held-out molecules ===")
    print(merged.to_string(index=False))

    s_old, s_new = summarize(m_old), summarize(m_new)
    print(f"\nold ({old_dir.name}): {json.dumps(s_old)}")
    print(f"new ({new_dir.name}): {json.dumps(s_new)}")

    ok = merged.dropna(subset=["d_auprc"])
    wins = int((ok["d_auprc"] > 0).sum())
    print(f"\nnew model better on {wins} of {len(ok)} classes; "
          f"mean AUPRC change {ok['d_auprc'].mean():+.4f}")

    out = Path(args.out) if args.out else new_dir / "version_comparison.json"
    out.write_text(json.dumps({
        "n_common_molecules": int(len(common)),
        "old": s_old, "new": s_new,
        "per_class": merged.to_dict(orient="records"),
    }, indent=2, default=float), encoding="utf-8")
    print(f"Written to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
