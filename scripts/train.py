"""
Stage 2 of the pipeline: train every model in the bundle.

    python scripts/train.py                       # baseline bundle only
    python scripts/train.py --dmpnn --epochs 20   # also train the D-MPNN
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob.dataset import load_dataset
from chemrob.keepawake import keep_awake
from chemrob.train import TrainConfig, train_all


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-mmp", action="store_true", help="skip matched-pair mining")
    ap.add_argument("--no-scaffold", action="store_true", help="skip the core-ring head")
    ap.add_argument("--no-targets", action="store_true", help="skip the per-target head")
    ap.add_argument("--no-regression", action="store_true",
                    help="skip the potency regression heads (disables selectivity)")
    ap.add_argument("--mmp-min-pairs", type=int, default=3)
    ap.add_argument("--dmpnn", action="store_true", help="also train the graph network")
    ap.add_argument("--dmpnn-only", action="store_true",
                    help="train only the graph network, leaving the baseline bundle "
                         "and its metrics untouched")
    ap.add_argument("--epochs", type=int, default=25)
    ap.add_argument("--batch-size", type=int, default=128)
    ap.add_argument("--hidden", type=int, default=256)
    ap.add_argument("--depth", type=int, default=3)
    ap.add_argument("--threads", type=int, default=8,
                    help="torch CPU threads for D-MPNN training")
    ap.add_argument("--max-train", type=int, default=None,
                    help="subsample the training split (useful for a quick run)")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )
    log = logging.getLogger("train")

    ds = load_dataset()

    if args.dmpnn_only:
        # Deliberately skips train_all: rerunning it would rewrite metrics.json
        # with only the heads enabled on this invocation, silently discarding the
        # target- and scaffold-head results from the previous full run.
        log.info("skipping the baseline bundle; training the D-MPNN only")
    else:
        cfg = TrainConfig(
            train_mmp=not args.no_mmp,
            train_scaffold=not args.no_scaffold,
            train_targets=not args.no_targets,
        train_regression=not args.no_regression,
            mmp_min_pairs=args.mmp_min_pairs,
        )
        out = train_all(ds, cfg)
        log.info("baseline summary: %s", out["metrics"]["activity"]["summary"])

    if args.dmpnn or args.dmpnn_only:
        from chemrob.train_dmpnn import train_dmpnn

        res = train_dmpnn(
            ds,
            epochs=args.epochs,
            batch_size=args.batch_size,
            hidden=args.hidden,
            depth=args.depth,
            max_train=args.max_train,
            threads=args.threads,
        )
        log.info("D-MPNN summary: %s", res["test_activity"]["summary"])

    return 0


if __name__ == "__main__":
    # Windows counts a long fit with no keyboard input as idle and will
    # suspend underneath it; that is what killed a seed run mid-way.
    with keep_awake("training"):
        raise SystemExit(main())
