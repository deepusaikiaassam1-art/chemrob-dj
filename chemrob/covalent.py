"""
Covalent-warhead detection.

The model has a structural blind spot that no probability can express. Labels
are built from IC50/Ki thresholds, and IC50 is the wrong measurement for a
covalent inhibitor: potency depends on preincubation time, so a drug that
permanently inactivates its target can still record a weak, unremarkable number.

Aspirin is the clean demonstration. ChEMBL records COX-2 at 2,399 nM and COX-1
at 30,000 nM, so against a 100 nM active threshold it is labelled inactive for
anti-inflammatory activity - and the model dutifully returns 0.011. That label
is *correct arithmetic on the wrong quantity*. Aspirin works by acetylating
COX serine-530 irreversibly; binding affinity was never the mechanism.

The same applies to beta-lactams, proton-pump inhibitors, and most targeted
covalent drugs. This module cannot fix the labels, but it can stop a user
reading "inactive" as "does nothing", by flagging the chemistry that makes the
number untrustworthy.
"""
from __future__ import annotations

from dataclasses import dataclass

from rdkit import Chem


@dataclass(frozen=True)
class Warhead:
    key: str
    name: str
    smarts: str
    mechanism: str
    example: str


WARHEADS: tuple[Warhead, ...] = (
    Warhead(
        "acrylamide", "Acrylamide (Michael acceptor)",
        "[NX3][CX3](=O)[CX3H1]=[CX3H2]",
        "Conjugate addition to an active-site cysteine thiol.",
        "ibrutinib, osimertinib, afatinib",
    ),
    Warhead(
        "vinyl_sulfone", "Vinyl sulfone",
        "[SX4](=O)(=O)[CX3]=[CX3]",
        "Michael acceptor for cysteine proteases.",
        "odanacatib-class cathepsin inhibitors",
    ),
    Warhead(
        "beta_lactam", "Beta-lactam",
        "[NX3]1[CX3](=O)[CX4][CX4]1",
        "Acylation of the transpeptidase active-site serine.",
        "penicillins, cephalosporins",
    ),
    Warhead(
        "aryl_ester", "Aryl ester (acetylating agent)",
        "[CX3](=O)[OX2][c]",
        "Transfers its acyl group to an active-site serine.",
        "aspirin",
    ),
    Warhead(
        "sulfinyl_benzimidazole", "Sulfinyl benzimidazole (prodrug)",
        "[SX3](=O)[c]1[nX3][c]2[c][c][c][c][c]2[nX2,nX3]1",
        "Rearranges in acid to a sulfenamide that disulfides with the H+/K+-ATPase.",
        "omeprazole, lansoprazole",
    ),
    Warhead(
        "nitrile_warhead", "Activated nitrile",
        "[NX1]#[CX2][CX4;!$(C(C#N)C#N)]",
        "Reversible-covalent thioimidate with a catalytic cysteine.",
        "nirmatrelvir, saxagliptin",
    ),
    Warhead(
        "epoxide_wh", "Epoxide",
        "[CX4]1[OX2][CX4]1",
        "Alkylation by strained-ring opening.",
        "carfilzomib, fosfomycin",
    ),
    Warhead(
        "aziridine_wh", "Aziridine",
        "[CX4]1[NX3][CX4]1",
        "Alkylation by strained-ring opening.",
        "mitomycin C",
    ),
    Warhead(
        "boronic_wh", "Boronic acid / ester",
        "[BX3]([OX2])[OX2]",
        "Reversible tetrahedral adduct with a catalytic serine or threonine.",
        "bortezomib, vaborbactam",
    ),
    Warhead(
        "haloacetamide", "Haloacetyl",
        # Mono- or di-halogenated only, and no fluorine: a trifluoroacetamide is
        # a stable capping group, not a warhead, and the looser pattern
        # [CX3](=O)[CX4][F,Cl,Br,I] flags nirmatrelvir's CF3 cap as reactive.
        "[CX3](=O)[CX4;H1,H2][Cl,Br,I]",
        "Direct alkylation of cysteine.",
        "cysteine-targeted probes",
    ),
    Warhead(
        "isothiocyanate_wh", "Isothiocyanate",
        "[NX2]=[CX2]=[SX1]",
        "Thiocarbamoylation of cysteine.",
        "sulforaphane",
    ),
    Warhead(
        "alpha_halo_ketone", "Alpha-halo ketone",
        "[#6][CX3](=O)[CX4]([F,Cl,Br,I])",
        "Alkylation of an active-site nucleophile.",
        "protease inactivators",
    ),
    Warhead(
        "disulfide_wh", "Disulfide",
        "[SX2][SX2]",
        "Thiol-disulfide exchange with a target cysteine.",
        "disulfiram metabolites",
    ),
)

_PATTERNS: dict[str, Chem.Mol] = {}
for _w in WARHEADS:
    _p = Chem.MolFromSmarts(_w.smarts)
    if _p is None:
        raise ValueError(f"Invalid warhead SMARTS for {_w.key!r}: {_w.smarts}")
    _PATTERNS[_w.key] = _p

WARHEAD_BY_KEY = {w.key: w for w in WARHEADS}


def detect_warheads(mol: Chem.Mol) -> list[dict]:
    """Every covalent warhead present, with the atoms involved."""
    out: list[dict] = []
    for key, patt in _PATTERNS.items():
        matches = mol.GetSubstructMatches(patt, uniquify=True)
        if not matches:
            continue
        w = WARHEAD_BY_KEY[key]
        out.append({
            "key": w.key,
            "name": w.name,
            "mechanism": w.mechanism,
            "example_drugs": w.example,
            "count": len(matches),
            "atoms": sorted({a for m in matches for a in m}),
        })
    return out


def covalent_caveat(mol: Chem.Mol) -> dict | None:
    """
    The warning attached to a report when covalent chemistry is present.

    Returns None for ordinary reversible chemistry, so the caveat only appears
    where it actually applies.
    """
    hits = detect_warheads(mol)
    if not hits:
        return None
    names = ", ".join(h["name"] for h in hits)
    return {
        "warheads": hits,
        "message": (
            f"Contains covalent chemistry ({names}). This model is trained on "
            "IC50/Ki thresholds, which systematically understate covalent and "
            "time-dependent inhibitors - their potency depends on incubation "
            "time rather than binding affinity. An 'inactive' call here is "
            "evidence about affinity, not about whether the compound works."
        ),
        "advice": (
            "Check the per-target potencies rather than the class call, and "
            "confirm against a time-dependent assay (kinact/KI) before "
            "concluding the compound is inactive."
        ),
    }
