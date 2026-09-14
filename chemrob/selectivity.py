"""
Selectivity scoring.

Potency alone does not make a drug. Most of the classical liabilities in this
project's target list are selectivity failures, not potency failures: a COX
inhibitor that hits COX-1 as hard as COX-2 causes GI bleeding, an MAO-B
inhibitor that also blocks MAO-A brings back the tyramine interaction, an
antidepressant's transporter profile decides its side-effect signature.

Selectivity index is a *ratio* of potencies, so on the pActivity scale it is a
subtraction:

    SI (log units) = pActivity(primary) - pActivity(anti-target)

+1.0 means ten-fold selective, +2.0 hundred-fold. This is why the regression
heads matter - the binary classifiers cannot express it at all.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .config import TARGET_BY_ID


@dataclass(frozen=True)
class SelectivityPair:
    key: str
    primary: str          # target we want hit
    anti_target: str      # target we want spared
    label: str
    rationale: str
    desirable_si: float = 1.0   # log units regarded as meaningful selectivity


# Pairs chosen because the selectivity has a known clinical consequence, not
# merely because both targets happen to be in the model.
SELECTIVITY_PAIRS: tuple[SelectivityPair, ...] = (
    SelectivityPair(
        "cox2_over_cox1", "CHEMBL230", "CHEMBL221",
        "COX-2 over COX-1",
        "COX-1 sparing is what separates a coxib from a classical NSAID; "
        "COX-1 inhibition drives gastric ulceration and bleeding.",
        1.0,
    ),
    SelectivityPair(
        "maob_over_maoa", "CHEMBL2039", "CHEMBL1951",
        "MAO-B over MAO-A",
        "MAO-A inhibition causes the tyramine 'cheese effect'; antiparkinson "
        "agents need MAO-B selectivity to avoid dietary restriction.",
        1.0,
    ),
    SelectivityPair(
        "ache_over_bche", "CHEMBL220", "CHEMBL1914",
        "AChE over BChE",
        "Relative cholinesterase selectivity shapes the peripheral "
        "cholinergic side-effect burden of anti-Alzheimer agents.",
        0.5,
    ),
    SelectivityPair(
        "sert_over_net", "CHEMBL228", "CHEMBL222",
        "SERT over NET",
        "Distinguishes an SSRI from an SNRI; drives the cardiovascular and "
        "discontinuation profile.",
        1.0,
    ),
    SelectivityPair(
        "sert_over_dat", "CHEMBL228", "CHEMBL238",
        "SERT over DAT",
        "DAT affinity carries stimulant and abuse-liability risk.",
        1.0,
    ),
    SelectivityPair(
        "kappa_over_mu", "CHEMBL237", "CHEMBL233",
        "Kappa over mu opioid",
        "Mu agonism carries the respiratory-depression and dependence risk; "
        "kappa-selective analgesia is a route around it.",
        1.0,
    ),
    SelectivityPair(
        "h1_over_h3", "CHEMBL231", "CHEMBL264",
        "H1 over H3",
        "H3 is a CNS autoreceptor; an antihistamine hitting it has unwanted "
        "central activity.",
        1.0,
    ),
    SelectivityPair(
        "egfr_over_vegfr2", "CHEMBL203", "CHEMBL279",
        "EGFR over VEGFR2",
        "Kinase cross-reactivity: VEGFR2 inhibition brings hypertension and "
        "bleeding risk to an EGFR-directed agent.",
        1.0,
    ),
    SelectivityPair(
        "cdk2_over_abl1", "CHEMBL301", "CHEMBL1862",
        "CDK2 over ABL1",
        "Broad kinase promiscuity is the usual cause of cytotoxicity in "
        "cell-cycle-directed agents.",
        1.0,
    ),
)

PAIR_BY_KEY = {p.key: p for p in SELECTIVITY_PAIRS}


def _verdict(si: float, threshold: float) -> str:
    if np.isnan(si):
        return "unknown"
    if si >= threshold:
        return "selective"
    if si <= -threshold:
        return "inverted (favours the anti-target)"
    return "non-selective"


def compute_selectivity(
    target_potency: dict[str, float],
    target_probability: dict[str, float] | None = None,
    *,
    min_probability: float = 0.10,
) -> list[dict]:
    """
    Selectivity for every pair where both targets have a predicted potency.

    `target_potency` maps ChEMBL target id -> predicted pActivity.

    A selectivity index computed between two potencies the model considers
    negligible is arithmetically fine and pharmacologically meaningless - being
    "100-fold selective" between two targets you do not bind is not a property.
    Pairs where neither target is plausibly engaged are therefore reported with
    `relevant: False` rather than silently ranked alongside real ones.
    """
    rows: list[dict] = []
    for pair in SELECTIVITY_PAIRS:
        p_primary = target_potency.get(pair.primary, float("nan"))
        p_anti = target_potency.get(pair.anti_target, float("nan"))
        if np.isnan(p_primary) or np.isnan(p_anti):
            continue

        si = float(p_primary - p_anti)
        relevant = True
        if target_probability:
            hit = max(
                target_probability.get(pair.primary, 0.0),
                target_probability.get(pair.anti_target, 0.0),
            )
            relevant = hit >= min_probability

        rows.append({
            "key": pair.key,
            "label": pair.label,
            "primary": {
                "chembl_id": pair.primary,
                "name": TARGET_BY_ID[pair.primary].name,
                "predicted_pactivity": round(float(p_primary), 2),
            },
            "anti_target": {
                "chembl_id": pair.anti_target,
                "name": TARGET_BY_ID[pair.anti_target].name,
                "predicted_pactivity": round(float(p_anti), 2),
            },
            "selectivity_index_log": round(si, 2),
            "fold_selectivity": round(float(10.0 ** si), 1),
            "verdict": _verdict(si, pair.desirable_si),
            "relevant": bool(relevant),
            "rationale": pair.rationale,
        })

    # Most selective first, but push the pharmacologically irrelevant ones down.
    return sorted(rows, key=lambda r: (not r["relevant"], -r["selectivity_index_log"]))


def promiscuity_score(target_potency: dict[str, float], threshold: float = 6.0) -> dict:
    """
    How many targets the molecule is predicted to engage.

    A compound predicted potent at most of 44 unrelated targets is far more
    likely to be a frequent hitter or an assay-interference compound than a
    genuine polypharmacology success, so this is reported as a caution.
    """
    vals = [v for v in target_potency.values() if not np.isnan(v)]
    if not vals:
        return {"n_targets_scored": 0}
    hits = [v for v in vals if v >= threshold]
    frac = len(hits) / len(vals)
    return {
        "n_targets_scored": len(vals),
        "n_predicted_active": len(hits),
        "fraction_active": round(frac, 3),
        "max_pactivity": round(float(max(vals)), 2),
        "flag": (
            "possible frequent hitter / assay interference"
            if frac > 0.5 and len(vals) >= 20
            else "selective profile" if frac < 0.2
            else "moderate polypharmacology"
        ),
    }
