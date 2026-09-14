"""
Fit the baseline / D-MPNN blend and report whether it actually helps.

    python scripts/train_ensemble.py

Requires both a trained baseline bundle and a D-MPNN checkpoint. Weights are
fitted per class on the validation split and evaluated on test, so the reported
gain is honest. If the blend does not beat the baseline the script says so and
writes the weights anyway - a table of zeros is a real result, not a failure.
"""
from __future__ import annotations

import json
import logging
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob.config import ARTIFACT_DIR, CLASS_KEYS
from chemrob.dataset import load_dataset
from chemrob.ensemble import apply_weights, fit_weights, save_weights
from chemrob.evaluate import evaluate_multitask, summarize
from chemrob.models.baseline import BaselineMultiTask
from chemrob.train import ART

WEIGHTS_PATH = ARTIFACT_DIR / "ensemble_weights.json"


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%H:%M:%S")
    log = logging.getLogger("ensemble")

    if not ART["dmpnn"].exists():
        print("No D-MPNN checkpoint. Train one first:\n"
              "  python scripts/train.py --dmpnn-only --epochs 30", file=sys.stderr)
        return 1

    from chemrob.train_dmpnn import load_dmpnn, predict_dmpnn

    ds = load_dataset()
    base = BaselineMultiTask.load(ART["activity"])
    model = load_dmpnn()

    va, te = ds.valid_idx, ds.test_idx
    log.info("scoring validation split with both models ...")
    P_base_va = base.predict_matrix(np.ascontiguousarray(ds.X[va]))
    P_dm_va, _ = predict_dmpnn(model, [ds.smiles[i] for i in va])

    log.info("fitting per-class blend weights on validation ...")
    weights = fit_weights(ds.Y_class[va], P_base_va, P_dm_va, list(CLASS_KEYS))
    save_weights(weights, WEIGHTS_PATH)

    log.info("scoring test split ...")
    P_base_te = base.predict_matrix(np.ascontiguousarray(ds.X[te]))
    P_dm_te, _ = predict_dmpnn(model, [ds.smiles[i] for i in te])
    P_ens_te = apply_weights(P_base_te, P_dm_te, weights, list(CLASS_KEYS))

    m_base = evaluate_multitask(ds.Y_class[te], P_base_te, list(CLASS_KEYS))
    m_dm = evaluate_multitask(ds.Y_class[te], P_dm_te, list(CLASS_KEYS))
    m_ens = evaluate_multitask(ds.Y_class[te], P_ens_te, list(CLASS_KEYS))

    merged = (
        m_base[["task", "n", "n_positive", "auprc"]]
        .rename(columns={"auprc": "auprc_baseline"})
        .merge(m_dm[["task", "auprc"]].rename(columns={"auprc": "auprc_dmpnn"}), on="task")
        .merge(m_ens[["task", "auprc"]].rename(columns={"auprc": "auprc_ensemble"}), on="task")
    )
    merged["weight"] = [weights.get(t, 0.0) for t in merged["task"]]
    merged["gain"] = (merged["auprc_ensemble"] - merged["auprc_baseline"]).round(4)

    print("\n=== Per-class AUPRC on the held-out scaffold split ===")
    print(merged.to_string(index=False))

    s_base, s_dm, s_ens = summarize(m_base), summarize(m_dm), summarize(m_ens)
    print("\nbaseline :", json.dumps(s_base))
    print("D-MPNN   :", json.dumps(s_dm))
    print("ensemble :", json.dumps(s_ens))

    gain = s_ens.get("macro_auprc", 0) - s_base.get("macro_auprc", 0)
    if gain > 0.002:
        print(f"\nEnsemble improves macro AUPRC by {gain:+.4f}. "
              f"It will be used automatically when --dmpnn is passed.")
    else:
        print(f"\nEnsemble changes macro AUPRC by {gain:+.4f} - not a meaningful gain. "
              f"The baseline alone remains the better default.")

    (ARTIFACT_DIR / "ensemble_metrics.json").write_text(
        json.dumps({
            "weights": weights,
            "baseline": s_base, "dmpnn": s_dm, "ensemble": s_ens,
            "per_class": merged.to_dict(orient="records"),
        }, indent=2, default=float),
        encoding="utf-8",
    )
    print(f"Written to {ARTIFACT_DIR / 'ensemble_metrics.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
