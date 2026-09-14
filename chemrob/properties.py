"""
Developability filters applied to generated analogues.

The deck's warning about pure generation is that it "can propose unmakeable
molecules". These are the gates that stop that: synthetic accessibility,
drug-likeness, Lipinski/Veber compliance, structural alerts, and novelty against
the training set.
"""
from __future__ import annotations

import os
import sys

from rdkit import Chem
from rdkit.Chem import Crippen, Descriptors, QED, RDConfig, rdMolDescriptors

from .fgroups import GROUP_BY_KEY, match_groups

# RDKit ships Ertl's synthetic-accessibility score as a contrib script rather
# than a library, so it has to be added to the path explicitly.
_SA_PATH = os.path.join(RDConfig.RDContribDir, "SA_Score")
if _SA_PATH not in sys.path:
    sys.path.append(_SA_PATH)
try:
    import sascorer  # type: ignore

    _HAS_SA = True
except Exception:  # noqa: BLE001
    _HAS_SA = False


def sa_score(mol: Chem.Mol) -> float:
    """Ertl synthetic accessibility, 1 (easy) to 10 (hard). NaN if unavailable."""
    if not _HAS_SA:
        return float("nan")
    try:
        return float(sascorer.calculateScore(mol))
    except Exception:  # noqa: BLE001
        return float("nan")


def lipinski(mol: Chem.Mol) -> dict:
    mw = Descriptors.MolWt(mol)
    logp = Crippen.MolLogP(mol)
    hbd = rdMolDescriptors.CalcNumHBD(mol)
    hba = rdMolDescriptors.CalcNumHBA(mol)
    violations = sum([mw > 500, logp > 5, hbd > 5, hba > 10])
    return {
        "mw": round(mw, 2), "logp": round(logp, 2), "hbd": hbd, "hba": hba,
        "violations": violations, "passes": violations <= 1,
    }


def veber(mol: Chem.Mol) -> dict:
    rotb = rdMolDescriptors.CalcNumRotatableBonds(mol)
    tpsa = rdMolDescriptors.CalcTPSA(mol)
    return {"rotatable_bonds": rotb, "tpsa": round(tpsa, 1),
            "passes": rotb <= 10 and tpsa <= 140}


def structural_alerts(mol: Chem.Mol) -> list[str]:
    return [
        GROUP_BY_KEY[k].name
        for k in match_groups(mol)
        if GROUP_BY_KEY[k].kind == "alert"
    ]


def profile(mol: Chem.Mol) -> dict:
    """The developability block attached to every suggested analogue."""
    lip = lipinski(mol)
    veb = veber(mol)
    alerts = structural_alerts(mol)
    return {
        "qed": round(float(QED.qed(mol)), 3),
        "sa_score": round(sa_score(mol), 2),
        "lipinski": lip,
        "veber": veb,
        "structural_alerts": alerts,
        "heavy_atoms": mol.GetNumHeavyAtoms(),
        "rings": rdMolDescriptors.CalcNumRings(mol),
        "developable": lip["passes"] and veb["passes"] and not alerts,
    }


def passes_filters(
    mol: Chem.Mol,
    *,
    max_sa: float = 6.0,
    min_qed: float = 0.25,
    allow_alerts: bool = False,
) -> tuple[bool, str]:
    """Hard gate used before a candidate is scored, with the reason on failure."""
    sa = sa_score(mol)
    if sa == sa and sa > max_sa:
        return False, f"synthetic accessibility {sa:.1f} > {max_sa}"
    q = float(QED.qed(mol))
    if q < min_qed:
        return False, f"QED {q:.2f} < {min_qed}"
    lip = lipinski(mol)
    if lip["violations"] >= 3:
        return False, f"{lip['violations']} Lipinski violations"
    if not allow_alerts:
        alerts = structural_alerts(mol)
        if alerts:
            return False, "structural alert: " + ", ".join(alerts)
    return True, ""
