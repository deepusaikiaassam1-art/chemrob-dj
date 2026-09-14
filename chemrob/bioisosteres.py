"""
A curated bioisostere library, used to seed the optimizer when the mined MMP
table has no precedent for a particular substituent.

These are standard medicinal-chemistry replacements (carboxylic acid surrogates,
halogen and metabolic-blocking swaps, ring equivalences). They are deliberately
kept separate from the mined rules: mined rules carry a measured delta from this
dataset, whereas these carry only general precedent, and the report labels the
source of every suggestion so the two are never confused.
"""
from __future__ import annotations

# Substituent-level replacements, written as attachment-point SMILES.
SUBSTITUENT_ISOSTERES: dict[str, list[str]] = {
    # Carboxylic-acid surrogates - the classic potency/PK trade set.
    "OC(=O)[*:1]": ["c1nnn[nH]1.[*:1]", "O=S(=O)(N)[*:1]", "O=C(NO)[*:1]",
                    "O=C(NS(=O)(=O)C)[*:1]"],
    # Halogen ladder and metabolic blocking.
    "F[*:1]": ["Cl[*:1]", "C[*:1]", "N#C[*:1]", "FC(F)(F)[*:1]"],
    "Cl[*:1]": ["F[*:1]", "Br[*:1]", "FC(F)(F)[*:1]", "C[*:1]", "N#C[*:1]"],
    "Br[*:1]": ["Cl[*:1]", "FC(F)(F)[*:1]", "C[*:1]"],
    "C[*:1]": ["FC(F)(F)[*:1]", "Cl[*:1]", "CC[*:1]", "CO[*:1]", "C1CC1[*:1]"],
    "CO[*:1]": ["FC(F)(F)O[*:1]", "CC[*:1]", "O[*:1]", "C[*:1]"],
    "O[*:1]": ["CO[*:1]", "N[*:1]", "F[*:1]", "O=S(=O)(N)[*:1]"],
    "N[*:1]": ["O[*:1]", "CN[*:1]", "CC(=O)N[*:1]"],
    "N#C[*:1]": ["FC(F)(F)[*:1]", "Cl[*:1]", "O=S(=O)(N)[*:1]"],
    "O=[N+]([O-])[*:1]": ["N#C[*:1]", "FC(F)(F)[*:1]", "O=S(=O)(N)[*:1]"],
    # Amide surrogates.
    "CC(=O)N[*:1]": ["O=S(=O)(C)N[*:1]", "CNC(=O)[*:1]", "O=C(N)[*:1]"],
    # Saturated-ring growth, a routine potency/solubility move.
    "C1CCNCC1[*:1]": ["C1CNCCN1[*:1]", "C1COCCN1[*:1]", "C1CCNC1[*:1]"],
}

# Whole-ring replacements used for scaffold hopping. Each family is a set of
# rings considered broadly interchangeable in binding terms.
#
# Every ring carries two forms, because they serve different purposes:
#   match   - a SMARTS used to find the ring in the query molecule. Azoles use
#             [nX3] so an N-substituted ring is found as readily as an N-H one.
#   replace - a valid SMILES used to build the product. A SMARTS cannot be used
#             here: query atoms carry no implicit-H count, so the product would
#             fail to sanitize.
RING_ISOSTERES: list[list[tuple[str, str]]] = [
    # six-membered aromatics
    [("c1ccccc1", "c1ccccc1"), ("c1ccncc1", "c1ccncc1"),
     ("c1cncnc1", "c1cncnc1"), ("c1ccsc1", "c1ccsc1"), ("c1ccoc1", "c1ccoc1")],
    # five-membered aromatics
    [("c1cc[nX3]c1", "c1cc[nH]c1"), ("c1cnc[nX3]1", "c1cnc[nH]1"),
     ("c1cn[nX3]c1", "c1cn[nH]c1"), ("c1ccsc1", "c1ccsc1"), ("c1ccoc1", "c1ccoc1")],
    # benzo-fused five-membered
    [("c1ccc2[nX3]ccc2c1", "c1ccc2[nH]ccc2c1"),
     ("c1ccc2[nX3]ncc2c1", "c1ccc2[nH]ncc2c1"),
     ("c1ccc2[nX3]cnc2c1", "c1ccc2[nH]cnc2c1"),
     ("c1ccc2occc2c1", "c1ccc2occc2c1")],
    # saturated nitrogen heterocycles
    [("C1CCNCC1", "C1CCNCC1"), ("C1CNCCN1", "C1CNCCN1"),
     ("C1COCCN1", "C1COCCN1"), ("C1CCNC1", "C1CCNC1"), ("C1CSCCN1", "C1CSCCN1")],
    # benzo-fused six-membered diazines
    [("c1ccc2ncccc2c1", "c1ccc2ncccc2c1"), ("c1ccc2cnccc2c1", "c1ccc2cnccc2c1"),
     ("c1ccc2ncncc2c1", "c1ccc2ncncc2c1"), ("c1ccc2nccnc2c1", "c1ccc2nccnc2c1")],
    # azole / azine bioisosteres
    [("c1nc[nX3]n1", "c1nc[nH]n1"), ("c1nnn[nX3]1", "c1nnn[nH]1"),
     ("c1cnc[nX3]1", "c1cnc[nH]1"), ("c1cscn1", "c1cscn1"), ("c1cocn1", "c1cocn1")],
]


def isosteres_for(sub_smiles: str) -> list[str]:
    """Replacements for a substituent, matched on canonical SMILES."""
    from rdkit import Chem

    mol = Chem.MolFromSmiles(sub_smiles)
    if mol is None:
        return []
    canon = Chem.MolToSmiles(mol)
    for key, values in SUBSTITUENT_ISOSTERES.items():
        km = Chem.MolFromSmiles(key)
        if km is None:
            continue
        if Chem.MolToSmiles(km) == canon:
            out = []
            for v in values:
                vm = Chem.MolFromSmiles(v)
                if vm is not None:
                    out.append(Chem.MolToSmiles(vm))
            return out
    return []
