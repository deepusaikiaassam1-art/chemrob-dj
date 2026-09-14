"""
Matched molecular pair mining (the knowledge half of Module 3).

Two molecules that differ by exactly one substituent, measured against the same
class, form a matched pair. The difference in their pActivity is attributable to
that single swap. Mining every such pair in the training set yields a table of
transformations of the form

    [*:1]Cl  ->  [*:1]C(F)(F)F     anticancer   mean delta pActivity +0.42  (n=118)

which is what lets the optimizer propose a *specific, precedented* edit rather
than "make it more lipophilic". This is the SwissBioisostere-style lookup the
deck contrasts with pure generation; optimize.py combines it with model scoring
so proposals are both precedented and predicted to help.
"""
from __future__ import annotations

import logging
from collections import defaultdict
from functools import lru_cache
from itertools import combinations

import numpy as np
import pandas as pd
from rdkit import Chem
from rdkit.Chem import rdMMPA

from .config import CLASS_KEYS

log = logging.getLogger(__name__)

# Guards against combinatorial blow-up on very common cores (e.g. plain benzene).
MAX_MEMBERS_PER_CORE = 40
MIN_CORE_HEAVY_ATOMS = 5
MAX_SUB_HEAVY_ATOMS = 14


@lru_cache(maxsize=200_000)
def _heavy_atoms(smi: str) -> int:
    """Cached: fragment SMILES repeat constantly across a large dataset, and
    re-parsing each one dominates fragmentation cost otherwise."""
    mol = Chem.MolFromSmiles(smi)
    return mol.GetNumHeavyAtoms() if mol is not None else 0


def single_cut_fragments(mol: Chem.Mol) -> list[tuple[str, str]]:
    """
    Every way of cutting one acyclic single bond, as (context, substituent).

    The larger fragment is the context that stays fixed; the smaller is the
    variable part whose replacement the rule describes.
    """
    try:
        frags = rdMMPA.FragmentMol(mol, maxCuts=1, resultsAsMols=False)
    except Exception:  # noqa: BLE001
        return []

    out: list[tuple[str, str]] = []
    for _, combined in frags:
        if not combined or "." not in combined:
            continue
        a, b = combined.split(".", 1)
        na, nb = _heavy_atoms(a), _heavy_atoms(b)
        if min(na, nb) == 0:
            continue
        core, sub = (a, b) if na >= nb else (b, a)
        if _heavy_atoms(core) < MIN_CORE_HEAVY_ATOMS:
            continue
        if _heavy_atoms(sub) > MAX_SUB_HEAVY_ATOMS:
            continue
        out.append((core, sub))
    return out


def build_fragment_index(smiles: list[str]) -> dict[str, list[tuple[str, int]]]:
    """context SMILES -> [(substituent SMILES, molecule index), ...]"""
    index: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for i, smi in enumerate(smiles):
        if i and i % 10000 == 0:
            log.info("  fragmenting %d / %d", i, len(smiles))
        mol = Chem.MolFromSmiles(smi)
        if mol is None:
            continue
        for core, sub in single_cut_fragments(mol):
            index[core].append((sub, i))
    log.info("fragment index: %d distinct contexts", len(index))
    return index


def mine_transformations(
    smiles: list[str],
    p_matrix: np.ndarray,
    class_keys: tuple[str, ...] = CLASS_KEYS,
    *,
    min_pairs: int = 3,
) -> pd.DataFrame:
    """
    Mine transformation rules.

    `p_matrix` is (n_molecules, n_classes) of pActivity values with NaN where the
    molecule was not measured for that class. A pair only contributes to a class
    where BOTH molecules have a measurement, which is what keeps the delta
    meaningful.
    """
    index = build_fragment_index(smiles)

    # Aggregated online rather than collected into a list first. A common
    # context such as "benzene minus one substituent" can have thousands of
    # members, and materialising every pairwise observation across ~10^5
    # molecules runs to tens of millions of rows. Running sums are bounded by
    # the number of distinct rules instead, which is orders of magnitude smaller.
    stats: dict[tuple[str, str, str], list[float]] = {}  # -> [n, sum, sumsq, n_improved]
    n_observations = 0

    for core, members in index.items():
        if len(members) < 2:
            continue
        if len(members) > MAX_MEMBERS_PER_CORE:
            members = members[:MAX_MEMBERS_PER_CORE]
        for (sub_a, i), (sub_b, j) in combinations(members, 2):
            if sub_a == sub_b or i == j:
                continue
            pa, pb = p_matrix[i], p_matrix[j]
            both = ~np.isnan(pa) & ~np.isnan(pb)
            if not both.any():
                continue
            for c in np.flatnonzero(both):
                delta = float(pb[c] - pa[c])
                key = class_keys[c]
                for a, b, d in ((sub_a, sub_b, delta), (sub_b, sub_a, -delta)):
                    s = stats.get((a, b, key))
                    if s is None:
                        stats[(a, b, key)] = [1.0, d, d * d, 1.0 if d > 0 else 0.0]
                    else:
                        s[0] += 1.0
                        s[1] += d
                        s[2] += d * d
                        s[3] += 1.0 if d > 0 else 0.0
                n_observations += 2

    if not stats:
        log.warning("no matched molecular pairs found")
        return pd.DataFrame(
            columns=["from_sub", "to_sub", "class_key", "n_pairs",
                     "mean_delta", "std_delta", "frac_improved",
                     "support_weight", "shrunk_delta"]
        )

    log.info("aggregated %d directed pair observations into %d candidate rules",
             n_observations, len(stats))

    kept = [(k, v) for k, v in stats.items() if v[0] >= min_pairs]
    n = np.array([v[0] for _, v in kept])
    total = np.array([v[1] for _, v in kept])
    total_sq = np.array([v[2] for _, v in kept])
    improved = np.array([v[3] for _, v in kept])
    mean = total / n
    var = np.maximum(total_sq / n - mean**2, 0.0)

    rules = pd.DataFrame({
        "from_sub": [k[0] for k, _ in kept],
        "to_sub": [k[1] for k, _ in kept],
        "class_key": [k[2] for k, _ in kept],
        "n_pairs": n.astype(int),
        "mean_delta": mean,
        "std_delta": np.sqrt(var),
        "frac_improved": improved / n,
    })

    # A rule with a big mean but only 3 noisy observations should not outrank a
    # steady one with 200. Shrink the mean towards zero by its own evidence.
    rules["support_weight"] = rules["n_pairs"] / (rules["n_pairs"] + 10.0)
    rules["shrunk_delta"] = rules["mean_delta"] * rules["support_weight"]

    log.info("mined %d transformation rules (>= %d pairs)", len(rules), min_pairs)
    return rules.sort_values(["class_key", "shrunk_delta"], ascending=[True, False])


def rules_for_substituent(
    rules: pd.DataFrame, sub: str, class_key: str, *, top_n: int = 25,
    min_delta: float = 0.15,
) -> pd.DataFrame:
    """Best precedented replacements for one substituent, for one activity class."""
    if rules.empty:
        return rules
    sel = rules[
        (rules["from_sub"] == sub)
        & (rules["class_key"] == class_key)
        & (rules["shrunk_delta"] >= min_delta)
    ]
    return sel.nlargest(top_n, "shrunk_delta")


def apply_transformation(core_smiles: str, new_sub_smiles: str) -> str | None:
    """Re-assemble a molecule from a context and a replacement substituent."""
    core = Chem.MolFromSmiles(core_smiles)
    sub = Chem.MolFromSmiles(new_sub_smiles)
    if core is None or sub is None:
        return None
    try:
        product = Chem.molzip(core, sub)
        Chem.SanitizeMol(product)
        return Chem.MolToSmiles(product)
    except Exception:  # noqa: BLE001
        return None
