"""
Stage 3: report held-out performance for a trained bundle.

    python scripts/evaluate.py              # activity head, per-task table
    python scripts/evaluate.py --head all   # activity, target and scaffold heads
    python scripts/evaluate.py --compare    # baseline vs D-MPNN on the same split
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob.config import ARTIFACT_DIR, CLASS_KEYS
from chemrob.dataset import load_dataset
from chemrob.evaluate import evaluate_multitask, summarize
from chemrob.models.baseline import BaselineMultiTask
from chemrob.train import ART


def print_table(per_task: list[dict], title: str) -> None:
    df = pd.DataFrame(per_task)
    if df.empty:
        print(f"\n{title}: nothing to report")
        return
    df = df[df.get("auroc").notna()] if "auroc" in df.columns else df
    cols = ["task", "n", "n_positive", "positive_rate", "auroc", "auprc",
            "auprc_lift", "f1", "mcc"]
    cols = [c for c in cols if c in df.columns]
    print(f"\n{title}")
    print("-" * 92)
    print(df[cols].to_string(index=False))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--head", choices=["activity", "target", "scaffold", "all"],
                    default="activity")
    ap.add_argument("--compare", action="store_true",
                    help="also evaluate the D-MPNN checkpoint on the same split")
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    metrics_path = ARTIFACT_DIR / "metrics.json"
    if not metrics_path.exists():
        print("No metrics.json - run scripts/train.py first.", file=sys.stderr)
        return 1
    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))

    heads = ["activity", "target", "scaffold"] if args.head == "all" else [args.head]
    for h in heads:
        if h not in metrics or not metrics[h]:
            continue
        print_table(metrics[h].get("per_task", []), f"[{h} head] held-out scaffold split")
        print(f"\nsummary: {json.dumps(metrics[h].get('summary', {}), indent=2)}")

    if args.compare:
        dm_path = ART["dmpnn"]
        if not dm_path.exists():
            print("\nNo D-MPNN checkpoint to compare against.")
            return 0
        from chemrob.train_dmpnn import load_dmpnn, predict_dmpnn

        ds = load_dataset()
        te = ds.test_idx
        model = load_dmpnn()
        act, _ = predict_dmpnn(model, [ds.smiles[i] for i in te])
        dm = evaluate_multitask(ds.Y_class[te], act, list(CLASS_KEYS))

        base = BaselineMultiTask.load(ART["activity"])
        bp = base.predict_matrix(ds.X[te])
        bm = evaluate_multitask(ds.Y_class[te], bp, list(CLASS_KEYS))

        print("\n[baseline vs D-MPNN] same scaffold-split test set")
        print("-" * 92)
        merged = bm[["task", "n", "n_positive", "auroc", "auprc"]].merge(
            dm[["task", "auroc", "auprc"]], on="task",
            suffixes=("_baseline", "_dmpnn"),
        )
        merged["auprc_delta"] = (
            merged["auprc_dmpnn"] - merged["auprc_baseline"]
        ).round(4)
        print(merged.to_string(index=False))
        print(f"\nbaseline: {json.dumps(summarize(bm))}")
        print(f"D-MPNN  : {json.dumps(summarize(dm))}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
