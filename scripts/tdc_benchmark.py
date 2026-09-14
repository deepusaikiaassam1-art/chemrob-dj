"""
External comparison against the Therapeutics Data Commons ADMET benchmarks.

The BindingDB run showed the model transfers to an independent database, but it
still produced an absolute number with nothing to place it against. These are
public benchmarks with published leaderboards, so the result lands on a scale
readers already know.

Five tasks map onto safety heads this model has:

    hERG (Karim)          -> herg_liability
    CYP2D6 / 3A4 / 2C9    -> cyp_inhibition
    P-gp (Broccatelli)    -> pgp_efflux

Two things make this an honest comparison rather than a flattering one:

  * Any benchmark molecule that appears in this project's training set is
    removed before scoring. ChEMBL and TDC overlap heavily on exactly these
    assays, so without the filter this would largely measure memorisation.
  * TDC leaderboard entries are trained *on the benchmark's own training split*
    and evaluated on its scaffold split. This model has never seen the
    benchmark at all, so it is doing zero-shot transfer against models fitted
    for the task. That asymmetry favours the leaderboard, and the comparison is
    reported with that stated rather than quietly ignored.

    python scripts/tdc_benchmark.py
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
from chemrob.config import ARTIFACT_DIR, CLASS_KEYS, DATA_DIR
from chemrob.evaluate import task_metrics
from chemrob.featurize import featurize
from chemrob.keepawake import keep_awake
from chemrob.models.baseline import BaselineMultiTask
from chemrob.standardize import standardize_smiles
from chemrob.train import ART

log = logging.getLogger("tdc")
OUT = ARTIFACT_DIR / "tdc_benchmark.json"
TDC_DIR = DATA_DIR / "tdc"

# Published leaderboard figures are AUROC for these binary ADMET tasks. Ranges
# are the spread of entries on the TDC leaderboard at the time of writing and
# are quoted only to place our number, not as a precise comparison.
TASKS = {
    "herg.tab": {
        "head": "herg_liability", "name": "hERG blockade (Karim et al.)",
        "leaderboard": "AUROC ~0.74-0.88 (models trained on this benchmark)",
    },
    "cyp2d6_veith.tab": {
        "head": "cyp2d6_inhibition", "name": "CYP2D6 inhibition (Veith et al.)",
        "leaderboard": "AUPRC ~0.62-0.74",
    },
    "cyp3a4_veith.tab": {
        "head": "cyp3a4_inhibition", "name": "CYP3A4 inhibition (Veith et al.)",
        "leaderboard": "AUPRC ~0.79-0.88",
    },
    "cyp2c9_veith.tab": {
        "head": "cyp2c9_inhibition", "name": "CYP2C9 inhibition (Veith et al.)",
        "leaderboard": "AUPRC ~0.72-0.83",
    },
    "pgp_broccatelli.tab": {
        "head": "pgp_efflux", "name": "P-gp inhibition (Broccatelli et al.)",
        "leaderboard": "AUROC ~0.90-0.94",
    },
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep-seen", action="store_true",
                    help="do not exclude molecules present in training "
                         "(shows how much of the result is memorisation)")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%H:%M:%S")

    model = BaselineMultiTask.load(ART["activity"])
    training = set(ApplicabilityDomain.load(ART["ad"]).smiles)
    log.info("training set holds %d molecules", len(training))

    rows = []
    for fname, spec in TASKS.items():
        path = TDC_DIR / fname
        if not path.exists():
            log.warning("%s not downloaded; skipping", fname)
            continue
        df = pd.read_csv(path, sep="\t")
        head = spec["head"]
        if head not in model.models:
            log.warning("no trained head for %s; skipping", head)
            continue

        std, labels, seen = [], [], 0
        for smi, y in zip(df["Drug"], df["Y"]):
            res = standardize_smiles(str(smi))
            if not res.ok:
                continue
            if res.smiles in training:
                seen += 1
                if not args.keep_seen:
                    continue
            std.append(res.smiles)
            labels.append(int(y))

        if len(std) < 50 or len(set(labels)) < 2:
            log.warning("%s: only %d usable molecules; skipping", fname, len(std))
            continue

        from rdkit import Chem, RDLogger

        RDLogger.DisableLog("rdApp.*")
        mols = [Chem.MolFromSmiles(s) for s in std]
        keep = [i for i, m in enumerate(mols) if m is not None]
        X = np.vstack([featurize(mols[i]) for i in keep])
        y = np.array([labels[i] for i in keep])

        j = CLASS_KEYS.index(head)
        p = model.predict_matrix(X)[:, j]
        m = task_metrics(y, p)

        rows.append({
            "benchmark": spec["name"], "file": fname, "head": head,
            "n_total": int(len(df)),
            "n_overlapping_training": int(seen),
            "n_scored": int(len(y)),
            "positive_rate": round(float(y.mean()), 4),
            "auroc": m.get("auroc"), "auprc": m.get("auprc"),
            "auprc_lift": m.get("auprc_lift"), "mcc": m.get("mcc"),
            "published_leaderboard": spec["leaderboard"],
        })
        log.info("%-34s n=%d (%d excluded as seen)  AUROC %.3f  AUPRC %.3f",
                 spec["name"], len(y), seen, m.get("auroc", float("nan")),
                 m.get("auprc", float("nan")))

    if not rows:
        print("No benchmark could be scored.", file=sys.stderr)
        return 1

    results = {
        "note": "Zero-shot transfer: this model was never trained on any TDC "
                "benchmark. Leaderboard entries are trained on each benchmark's "
                "own split, so the comparison is not like-for-like and favours "
                "them.",
        "excluded_training_overlap": not args.keep_seen,
        "per_benchmark": rows,
    }
    OUT.write_text(json.dumps(results, indent=2, default=float), encoding="utf-8")

    print("\n" + "=" * 92)
    print("EXTERNAL COMPARISON - Therapeutics Data Commons ADMET benchmarks")
    print("=" * 92)
    print("Zero-shot: the model was never trained on these. Molecules also "
          "present in its training set are excluded.\n")
    df = pd.DataFrame(rows)
    print(df[["benchmark", "n_scored", "n_overlapping_training", "positive_rate",
              "auroc", "auprc", "mcc"]].to_string(index=False))
    print("\nPublished leaderboard ranges (models trained on each benchmark):")
    for r in rows:
        print(f"  {r['benchmark']:<38}{r['published_leaderboard']}")
    print(f"\nWritten to {OUT}")
    return 0


if __name__ == "__main__":
    # Windows counts a long fit with no keyboard input as idle and will
    # suspend underneath it; that is what killed a seed run mid-way.
    with keep_awake("the benchmark run"):
        raise SystemExit(main())
