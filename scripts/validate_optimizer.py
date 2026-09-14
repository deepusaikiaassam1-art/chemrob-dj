"""
Retrospective validation of the optimization engine.

Every other module in this project is measured. The optimizer - the part that
makes the tool interesting - returns ranked structural edits with a predicted
potency gain, and nothing has ever checked whether those gains are real. This
closes that.

The test is a matched-pair hold-out:

  1. Transformation rules are mined on the training split only. (train.py
     already does this, so the saved rule table carries no test information.)
  2. Matched molecular pairs are found *within the held-out split*: two test
     molecules sharing a core and differing by one substituent.
  3. For every held-out pair whose transformation appears in the training rule
     table, the rule's predicted change in pActivity is compared against the
     change actually measured.

Reported: sign agreement, Spearman and Pearson correlation, and mean absolute
error in log units, each stratified by how much evidence the rule rests on.

Two controls keep the headline honest:

  random rules   the same held-out pairs scored with a rule drawn at random for
                 that class. Sign agreement should sit at 50%; anything the real
                 rules achieve above that is the signal.
  shuffled       predicted deltas permuted across pairs, which breaks the
                 pairing while preserving the marginal distribution.

    python scripts/validate_optimizer.py
    python scripts/validate_optimizer.py --min-support 10
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from collections import defaultdict
from itertools import combinations
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob.config import ARTIFACT_DIR, CLASS_KEYS, THERAPEUTIC_CLASS_KEYS
from chemrob.dataset import load_dataset
from chemrob.keepawake import keep_awake
from chemrob.mmp import MAX_MEMBERS_PER_CORE, single_cut_fragments
from chemrob.train import ART

log = logging.getLogger("optimizer-validation")
OUT = ARTIFACT_DIR / "optimizer_validation.json"

# A pair whose measured potencies differ by less than this is inside assay
# noise; counting its sign as a hit or a miss is counting a coin flip.
MIN_OBSERVED_DELTA = 0.3


def held_out_pairs(ds, test_idx: np.ndarray) -> list[dict]:
    """
    Every matched molecular pair inside the held-out split.

    Both molecules must be in the test set, so nothing here was seen during
    training, and both must carry a measured potency for the same class.
    """
    smiles = [ds.smiles[i] for i in test_idx]
    pmax = ds.frame.iloc[test_idx][[f"pmax_{k}" for k in CLASS_KEYS]].to_numpy(
        dtype=np.float32
    )

    index: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for local, smi in enumerate(smiles):
        if local and local % 5000 == 0:
            log.info("  fragmenting %d / %d", local, len(smiles))
        mol = _mol(smi)
        if mol is None:
            continue
        for core, sub in single_cut_fragments(mol):
            index[core].append((sub, local))

    pairs: list[dict] = []
    for core, members in index.items():
        if len(members) < 2:
            continue
        if len(members) > MAX_MEMBERS_PER_CORE:
            members = members[:MAX_MEMBERS_PER_CORE]
        for (sub_a, i), (sub_b, j) in combinations(members, 2):
            if sub_a == sub_b or i == j:
                continue
            both = ~np.isnan(pmax[i]) & ~np.isnan(pmax[j])
            for c in np.flatnonzero(both):
                key = CLASS_KEYS[c]
                if key not in THERAPEUTIC_CLASS_KEYS:
                    continue
                pairs.append({
                    "from_sub": sub_a, "to_sub": sub_b, "class_key": key,
                    "observed_delta": float(pmax[j][c] - pmax[i][c]),
                })
    log.info("held-out matched pairs: %d", len(pairs))
    return pairs


def _mol(smi: str):
    from rdkit import Chem, RDLogger

    RDLogger.DisableLog("rdApp.*")
    return Chem.MolFromSmiles(smi)


def _stats(observed: np.ndarray, predicted: np.ndarray, label: str) -> dict:
    from scipy.stats import pearsonr, spearmanr

    n = len(observed)
    if n < 20:
        return {"stratum": label, "n": int(n)}
    agree = float(np.mean(np.sign(observed) == np.sign(predicted)))
    out = {
        "stratum": label,
        "n": int(n),
        "sign_agreement": round(agree, 4),
        "mae_log_units": round(float(np.mean(np.abs(observed - predicted))), 4),
        "mean_observed": round(float(observed.mean()), 4),
        "mean_predicted": round(float(predicted.mean()), 4),
    }
    if len(np.unique(predicted)) > 2:
        rho, p_rho = spearmanr(observed, predicted)
        r, p_r = pearsonr(observed, predicted)
        out.update({
            "spearman": round(float(rho), 4), "spearman_p": float(p_rho),
            "pearson": round(float(r), 4), "pearson_p": float(p_r),
        })
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-support", type=int, default=3,
                    help="minimum matched pairs behind a training rule")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%H:%M:%S")

    if not ART["mmp"].exists():
        print("No mined rule table; run scripts/train.py first.", file=sys.stderr)
        return 1
    rules = pd.read_parquet(ART["mmp"])
    log.info("training rule table: %d rules", len(rules))

    ds = load_dataset()
    pairs = held_out_pairs(ds, ds.test_idx)
    if not pairs:
        print("No matched pairs found in the held-out split.", file=sys.stderr)
        return 1

    lookup = {
        (r.from_sub, r.to_sub, r.class_key): (r.mean_delta, r.n_pairs)
        for r in rules.itertuples()
    }

    rows: list[dict] = []
    for p in pairs:
        hit = lookup.get((p["from_sub"], p["to_sub"], p["class_key"]))
        if hit is None:
            continue
        pred, support = hit
        if support < args.min_support:
            continue
        rows.append({**p, "predicted_delta": float(pred), "support": int(support)})

    if not rows:
        print("No held-out pair matched a training rule.", file=sys.stderr)
        return 1
    df = pd.DataFrame(rows)
    log.info("held-out pairs covered by a training rule: %d (%.1f%% of pairs)",
             len(df), 100 * len(df) / len(pairs))

    # Pairs whose measured change is inside assay noise carry no signal either
    # way, so the headline is computed on the rest and both are reported.
    strong = df[df["observed_delta"].abs() >= MIN_OBSERVED_DELTA]

    results = {
        "n_held_out_pairs": int(len(pairs)),
        "n_covered_by_rules": int(len(df)),
        "coverage_fraction": round(len(df) / len(pairs), 4),
        "min_support": args.min_support,
        "min_observed_delta": MIN_OBSERVED_DELTA,
        "overall": _stats(df["observed_delta"].to_numpy(),
                          df["predicted_delta"].to_numpy(), "all pairs"),
        "meaningful_change_only": _stats(strong["observed_delta"].to_numpy(),
                                         strong["predicted_delta"].to_numpy(),
                                         f"|observed| >= {MIN_OBSERVED_DELTA}"),
        "by_support": [],
        "controls": {},
    }

    for lo, hi in [(3, 5), (5, 10), (10, 25), (25, 10**9)]:
        sel = strong[(strong["support"] >= lo) & (strong["support"] < hi)]
        label = f"support {lo}-{hi if hi < 10**9 else 'inf'}"
        s = _stats(sel["observed_delta"].to_numpy(),
                   sel["predicted_delta"].to_numpy(), label)
        if s.get("n", 0) >= 20:
            results["by_support"].append(s)

    rng = np.random.default_rng(args.seed)
    shuffled = strong["predicted_delta"].to_numpy().copy()
    rng.shuffle(shuffled)
    results["controls"]["shuffled_predictions"] = _stats(
        strong["observed_delta"].to_numpy(), shuffled, "shuffled control")

    by_class = {k: g["mean_delta"].to_numpy() for k, g in rules.groupby("class_key")}
    rand = np.array([
        float(rng.choice(by_class[c])) if c in by_class and len(by_class[c]) else 0.0
        for c in strong["class_key"]
    ])
    results["controls"]["random_rule"] = _stats(
        strong["observed_delta"].to_numpy(), rand, "random rule control")

    print("\n" + "=" * 82)
    print("OPTIMIZER VALIDATION - training rules against held-out matched pairs")
    print("=" * 82)
    print(f"held-out pairs {results['n_held_out_pairs']:,}   "
          f"covered by a rule {results['n_covered_by_rules']:,} "
          f"({100 * results['coverage_fraction']:.1f}%)")
    table = [results["overall"], results["meaningful_change_only"],
             *results["by_support"],
             results["controls"]["shuffled_predictions"],
             results["controls"]["random_rule"]]
    out = pd.DataFrame([t for t in table if t.get("n", 0) >= 20])
    cols = [c for c in ("stratum", "n", "sign_agreement", "spearman", "spearman_p",
                        "mae_log_units") if c in out.columns]
    print()
    print(out[cols].to_string(index=False))

    OUT.write_text(json.dumps(results, indent=2, default=float), encoding="utf-8")
    print(f"\nWritten to {OUT}")
    return 0


if __name__ == "__main__":
    # Windows counts a long fit with no keyboard input as idle and will
    # suspend underneath it; that is what killed a seed run mid-way.
    with keep_awake("optimizer validation"):
        raise SystemExit(main())
