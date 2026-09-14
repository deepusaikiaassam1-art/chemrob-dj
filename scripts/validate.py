"""
Rigorous validation: split-variance error bars and prospective (time) evaluation.

    python scripts/validate.py --years                 # year coverage, pick a cutoff
    python scripts/validate.py --time-split 2018       # prospective estimate
    python scripts/validate.py --seeds 1 2 3           # error bars over splits
    python scripts/validate.py --seeds 1 2 3 --time-split 2018 --compare

Each seed retrains the activity head, so `--seeds` costs roughly one training
run per seed. `--time-split` costs a single run and is the more informative of
the two if you only have time for one.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob.config import ARTIFACT_DIR
from chemrob.dataset import load_dataset
from chemrob.keepawake import keep_awake
from chemrob.validation import (
    compare_splits,
    compare_splits_by_block,
    multiseed_scaffold,
    time_split_evaluation,
    year_coverage,
)

OUT = ARTIFACT_DIR / "validation.json"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", type=int, nargs="*", default=None,
                    help="scaffold-split seeds for error bars, e.g. --seeds 1 2 3")
    ap.add_argument("--time-split", type=int, default=None, metavar="YEAR",
                    help="train on <= YEAR, test on later publications")
    ap.add_argument("--years", action="store_true",
                    help="print the dataset's year distribution and exit")
    ap.add_argument("--compare", action="store_true",
                    help="report scaffold-split optimism against the time split")
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%H:%M:%S")

    if not args.seeds and args.time_split is None and not args.compare and not args.years:
        print("Nothing to do. Pass --seeds, --time-split, --compare, or --years.",
              file=sys.stderr)
        return 2

    # --compare on its own recomputes the optimism blocks from artifacts already
    # on disk. That needs no molecules, and loading the dataset for it costs two
    # minutes for nothing.
    needs_dataset = bool(args.seeds) or args.time_split is not None or args.years
    ds = load_dataset() if needs_dataset else None

    if args.years:
        cov = year_coverage(ds)
        print(cov.to_string(index=False))
        if "cumulative_fraction" in cov.columns:
            print("\nSuggested cutoffs (fraction of data left for testing):")
            for frac in (0.30, 0.20, 0.10):
                row = cov[cov["cumulative_fraction"] <= 1 - frac].tail(1)
                if not row.empty:
                    print(f"  {int(row['year'].iloc[0])} -> ~{frac:.0%} of molecules held out")
        return 0

    # --seeds and --time-split are run separately (each is an expensive retrain,
    # and running both in one process is what got an earlier attempt killed), so
    # this run must add its block without erasing the other's. Only the keys
    # produced here are written; everything else on disk is left alone.
    #
    # Seeding a dict from the file at startup is NOT sufficient, and getting that
    # wrong cost a backfill: a --seeds run read the file at 08:33, held the
    # snapshot for 33 minutes, and wrote it back at 09:06, silently reverting
    # two blocks that had been added in between. The re-read therefore happens at
    # write time, not at start.
    results: dict = {}

    if args.seeds:
        results["multiseed_scaffold"] = multiseed_scaffold(ds, list(args.seeds))
        h = results["multiseed_scaffold"]["headline"]
        print("\n=== Scaffold-split variance over seeds", args.seeds, "===")
        print(f"macro AUROC {h['macro_auroc_mean']:.4f} +/- {h['macro_auroc_sd']:.4f}")
        print(f"macro AUPRC {h['macro_auprc_mean']:.4f} +/- {h['macro_auprc_sd']:.4f}")
        df = pd.DataFrame(results["multiseed_scaffold"]["per_class"])
        print("\nPer class (mean +/- SD across seeds):")
        for _, r in df.iterrows():
            print(f"  {r['task'][:22]:<24}AUROC {r['auroc_mean']:.3f} +/- {r['auroc_sd']:.3f}"
                  f"   AUPRC {r['auprc_mean']:.3f} +/- {r['auprc_sd']:.3f}")

    if args.time_split is not None:
        results["time_split"] = time_split_evaluation(ds, args.time_split)
        ts = results["time_split"]
        if "error" in ts:
            print(f"\nTime split failed: {ts['error']}")
        else:
            print(f"\n=== Prospective (time) split at {ts['cutoff_year']} ===")
            print(f"train={ts['n_train']}  test={ts['n_test']}")
            print(json.dumps(ts["summary"], indent=2))

    if args.compare and "time_split" not in results and OUT.exists():
        # --compare is meaningful against a time split this run did not itself
        # produce, e.g. `--seeds 1 2 3 --compare` after an earlier --time-split.
        # `results` no longer starts life pre-loaded, so fetch it explicitly.
        try:
            prior = json.loads(OUT.read_text(encoding="utf-8")).get("time_split")
            if prior and "error" not in prior:
                logging.info("comparing against the time split already on disk "
                             "(cutoff %s)", prior.get("cutoff_year"))
                results["time_split"] = prior
        except Exception:
            logging.warning("could not read a prior time split from %s", OUT)

    if args.compare and "time_split" in results:
        scaffold_summary = None
        scaffold_by_block = {}

        # The per-block reference always comes from the frozen build: a seed
        # sweep reports only pooled means, so taking the block figures from the
        # headline is impossible and skipping them silently is how --seeds
        # --compare ended up producing no per-block optimism at all.
        mp = ARTIFACT_DIR / "metrics.json"
        if mp.exists():
            act = json.loads(mp.read_text(encoding="utf-8"))["activity"]
            scaffold_summary = act["summary"]
            scaffold_by_block = act.get("summary_by_block") or {}

        if args.seeds:
            # Prefer the seed mean for the pooled figure when we have it; it is
            # the more honest scaffold-side estimate.
            h = results["multiseed_scaffold"]["headline"]
            scaffold_summary = {"macro_auroc": h["macro_auroc_mean"],
                                "macro_auprc": h["macro_auprc_mean"]}

        if scaffold_summary:
            cmp = compare_splits(scaffold_summary, results["time_split"])
            results["comparison"] = cmp
            print("\n=== Scaffold-split optimism (pooled over all heads) ===")
            for k, v in cmp.items():
                print(f"  {k:<14} scaffold {v['scaffold_split']:.4f}   "
                      f"time {v['time_split']:.4f}   optimism {v['optimism']:+.4f}")
        if scaffold_by_block:
            # The pooled figure above mixes head groups with very different base
            # rates. Report each block on its own so a therapeutic-only table is
            # never captioned with a blended optimism.
            cmpb = compare_splits_by_block(scaffold_by_block, results["time_split"])
            results["comparison_by_block"] = cmpb
            for block, rows in cmpb.items():
                print(f"\n=== Scaffold-split optimism - {block} "
                      f"({rows.get('n_tasks')} heads) ===")
                for k, v in rows.items():
                    if not isinstance(v, dict):
                        continue
                    print(f"  {k:<14} scaffold {v['scaffold_split']:.4f}   "
                          f"time {v['time_split']:.4f}   optimism {v['optimism']:+.4f}")

    merged: dict = {}
    if OUT.exists():
        try:
            merged = json.loads(OUT.read_text(encoding="utf-8"))
            kept = [k for k in merged if k not in results]
            if kept:
                logging.info("keeping existing blocks: %s", ", ".join(kept))
        except Exception:
            logging.warning("could not read %s; writing this run's blocks only", OUT)
    merged.update(results)
    OUT.write_text(json.dumps(merged, indent=2, default=float), encoding="utf-8")
    written = [k for k in results if k != "time_split" or args.time_split is not None]
    print(f"\nWritten to {OUT} ({', '.join(written)} updated)")
    return 0


if __name__ == "__main__":
    # Windows counts a long fit with no keyboard input as idle and will
    # suspend underneath it; that is what killed a seed run mid-way.
    with keep_awake("the validation run"):
        raise SystemExit(main())
