"""
Layer 1 - Input and standardization.

Turns whatever the user pasted or drew into one canonical molecule, so that the
same compound written three different ways produces one identical record.
Order of operations follows the usual ChEMBL-style structure pipeline:
sanitize -> remove salts / keep parent -> neutralize charges -> normalize
functional-group tautomers -> canonicalize.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

from rdkit import Chem, RDLogger
from rdkit.Chem import Descriptors
from rdkit.Chem.MolStandardize import rdMolStandardize

RDLogger.DisableLog("rdApp.*")
log = logging.getLogger(__name__)

_normalizer = rdMolStandardize.Normalizer()
_uncharger = rdMolStandardize.Uncharger()
_lfc = rdMolStandardize.LargestFragmentChooser()
_te = rdMolStandardize.TautomerEnumerator()

# Molecules outside these bounds are almost always assay artefacts, polymers or
# peptides rather than small-molecule drug candidates.
MIN_HEAVY_ATOMS = 6
MAX_HEAVY_ATOMS = 100
MAX_MOLWT = 1000.0

_ORGANIC = {"C", "N", "O", "S", "P", "F", "Cl", "Br", "I", "B", "H", "Se", "Si"}


@dataclass
class StandardizationResult:
    ok: bool
    smiles: str | None = None
    inchikey: str | None = None
    mol: Chem.Mol | None = None
    reason: str | None = None


def _is_organic(mol: Chem.Mol) -> bool:
    return all(a.GetSymbol() in _ORGANIC for a in mol.GetAtoms())


def standardize_mol(mol: Chem.Mol, *, canonical_tautomer: bool = False) -> StandardizationResult:
    """Run the full standardization pipeline on an already-parsed molecule."""
    if mol is None:
        return StandardizationResult(False, reason="null molecule")
    try:
        mol = Chem.Mol(mol)
        Chem.SanitizeMol(mol)
        mol = _lfc.choose(mol)          # strip salts / counter-ions, keep parent
        mol = _normalizer.normalize(mol)  # nitro groups, azides, sulfoxides, ...
        mol = _uncharger.uncharge(mol)    # neutralize where chemically sensible
        if canonical_tautomer:
            mol = _te.Canonicalize(mol)
        Chem.SanitizeMol(mol)
        Chem.AssignStereochemistry(mol, cleanIt=True, force=True)
    except Exception as exc:  # noqa: BLE001 - RDKit raises a wide range of errors
        return StandardizationResult(False, reason=f"standardization failed: {exc}")

    heavy = mol.GetNumHeavyAtoms()
    if heavy < MIN_HEAVY_ATOMS:
        return StandardizationResult(False, reason=f"too small ({heavy} heavy atoms)")
    if heavy > MAX_HEAVY_ATOMS:
        return StandardizationResult(False, reason=f"too large ({heavy} heavy atoms)")
    if Descriptors.MolWt(mol) > MAX_MOLWT:
        return StandardizationResult(False, reason="molecular weight above 1000 Da")
    if not _is_organic(mol):
        return StandardizationResult(False, reason="contains non-organic elements")
    if mol.GetRingInfo().NumRings() == 0 and heavy < 10:
        return StandardizationResult(False, reason="acyclic fragment")

    smi = Chem.MolToSmiles(mol, canonical=True)
    try:
        key = Chem.MolToInchiKey(mol)
    except Exception:  # noqa: BLE001
        key = None
    return StandardizationResult(True, smiles=smi, inchikey=key, mol=mol)


def standardize_smiles(smiles: str, *, canonical_tautomer: bool = False) -> StandardizationResult:
    """Parse and standardize a SMILES string coming from the UI or a data file."""
    if not smiles or not str(smiles).strip():
        return StandardizationResult(False, reason="empty input")
    mol = Chem.MolFromSmiles(str(smiles).strip())
    if mol is None:
        return StandardizationResult(False, reason="SMILES could not be parsed by RDKit")
    return standardize_mol(mol, canonical_tautomer=canonical_tautomer)


def parse_user_structure(text: str) -> StandardizationResult:
    """
    Accept the three things a user can realistically paste into the input box:
    a SMILES string, a molblock copied out of a sketcher, or an InChI.
    """
    if not text or not text.strip():
        return StandardizationResult(False, reason="empty input")
    raw = text.strip()

    if raw.upper().startswith("INCHI="):
        mol = Chem.MolFromInchi(raw)
        if mol is None:
            return StandardizationResult(False, reason="InChI could not be parsed")
        return standardize_mol(mol)

    # A molblock always has the counts line as its 4th line and usually 'V2000'.
    if "\n" in raw and ("V2000" in raw or "V3000" in raw):
        mol = Chem.MolFromMolBlock(raw)
        if mol is None:
            return StandardizationResult(False, reason="molblock could not be parsed")
        return standardize_mol(mol)

    return standardize_smiles(raw)
