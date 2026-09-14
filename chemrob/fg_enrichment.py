"""
Functional-group -> activity association, learned from the training set.

This is the "deep search" half of Module 1 in the deck: having detected that a
molecule contains, say, a benzenesulfonamide, say which activities that group is
historically tied to. Rather than hard-coding textbook associations, the
enrichment is measured on the same curated data the model trains on, so the
numbers are auditable and move when the dataset is rebuilt.

For each (group, class) pair we compute the active rate among molecules
containing the group versus those that do not, and score it with a one-sided
Fisher exact test. Only the training split is used, so the association table
cannot leak test-set information into an evaluation.
"""
from __future__ import annotations

import logging

import numpy as np
import pandas as pd
from rdkit import Chem
from scipy.stats import fisher_exact

from .config import CLASS_KEYS
from .fgroups import GROUP_BY_KEY, GROUP_KEYS, group_vector

log = logging.getLogger(__name__)

MIN_GROUP_MOLECULES = 25
MIN_ACTIVES_WITH_GROUP = 5


def group_matrix(smiles: list[str]) -> np.ndarray:
    """(n_molecules, n_groups) binary presence matrix."""
    M = np.zeros((len(smiles), len(GROUP_KEYS)), dtype=np.int8)
    for i, smi in enumerate(smiles):
        if i and i % 10000 == 0:
            log.info("  group matching %d / %d", i, len(smiles))
        mol = Chem.MolFromSmiles(smi)
        if mol is not None:
            M[i] = group_vector(mol)
    return M


def compute_enrichment(smiles: list[str], Y_class: np.ndarray) -> pd.DataFrame:
    """Enrichment table over every (functional group, activity class) pair."""
    M = group_matrix(smiles)
    rows: list[dict] = []

    for c, ckey in enumerate(CLASS_KEYS):
        y = Y_class[:, c]
        labelled = ~np.isnan(y)
        if labelled.sum() < 50:
            continue
        y_lab = y[labelled].astype(int)
        M_lab = M[labelled]
        base_rate = float(y_lab.mean())

        for g, gkey in enumerate(GROUP_KEYS):
            present = M_lab[:, g] == 1
            n_present = int(present.sum())
            if n_present < MIN_GROUP_MOLECULES:
                continue
            a = int(y_lab[present].sum())            # group present, active
            b = n_present - a                        # group present, inactive
            c_ = int(y_lab[~present].sum())          # absent, active
            d = int((~present).sum()) - c_           # absent, inactive
            if a < MIN_ACTIVES_WITH_GROUP:
                continue

            rate = a / n_present
            try:
                odds, p = fisher_exact([[a, b], [c_, d]], alternative="greater")
            except Exception:  # noqa: BLE001
                odds, p = float("nan"), 1.0

            rows.append({
                "group_key": gkey,
                "group_name": GROUP_BY_KEY[gkey].name,
                "group_kind": GROUP_BY_KEY[gkey].kind,
                "class_key": ckey,
                "n_with_group": n_present,
                "n_active_with_group": a,
                "active_rate_with_group": round(rate, 4),
                "base_rate": round(base_rate, 4),
                "enrichment": round(rate / base_rate, 3) if base_rate > 0 else np.nan,
                "odds_ratio": round(float(odds), 3) if odds == odds else np.nan,
                "p_value": float(p),
            })

    df = pd.DataFrame(rows)
    if df.empty:
        return df

    # Benjamini-Hochberg across the whole table; ~100 groups x 14 classes means
    # a raw p < 0.05 would produce dozens of false associations by chance alone.
    df = df.sort_values("p_value").reset_index(drop=True)
    m = len(df)
    ranks = np.arange(1, m + 1)
    df["q_value"] = np.minimum.accumulate((df["p_value"] * m / ranks)[::-1])[::-1]
    df["significant"] = (df["q_value"] < 0.05) & (df["enrichment"] > 1.2)
    log.info("enrichment table: %d rows, %d significant", m, int(df["significant"].sum()))
    return df.sort_values(["class_key", "enrichment"], ascending=[True, False])


def associations_for_groups(
    enrichment: pd.DataFrame, group_keys: list[str], *, top_n: int = 5,
    significant_only: bool = True,
) -> list[dict]:
    """
    For the groups found in a query molecule, the activity classes they are most
    enriched for. This is what the UI shows under "functional-group deep search".
    """
    if enrichment.empty or not group_keys:
        return []
    sel = enrichment[enrichment["group_key"].isin(group_keys)]
    if significant_only:
        sel = sel[sel["significant"]]
    if sel.empty:
        return []

    out: list[dict] = []
    for gkey, grp in sel.groupby("group_key"):
        best = grp.nlargest(top_n, "enrichment")
        out.append({
            "group_key": gkey,
            "group_name": GROUP_BY_KEY[gkey].name,
            "associations": [
                {
                    "class_key": r["class_key"],
                    "enrichment": float(r["enrichment"]),
                    "active_rate": float(r["active_rate_with_group"]),
                    "base_rate": float(r["base_rate"]),
                    "n_molecules": int(r["n_with_group"]),
                    "q_value": float(r["q_value"]),
                }
                for _, r in best.iterrows()
            ],
        })
    return sorted(
        out,
        key=lambda d: -max((a["enrichment"] for a in d["associations"]), default=0.0),
    )
