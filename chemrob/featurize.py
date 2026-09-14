"""
Layer 2 - Molecular representation.

Two parallel encodings, exactly as in the architecture slide:

  * ECFP4 count fingerprint + physicochemical descriptors -> the fixed-length
    vector consumed by the gradient-boosting baseline.
  * An atom/bond feature graph -> the tensors consumed by the D-MPNN.

The Morgan bit-info map is kept because the explainability layer needs to know
which atoms switched on which fingerprint bit.
"""
from __future__ import annotations

import numpy as np
from rdkit import Chem, DataStructs
from rdkit.Chem import Crippen, Descriptors, QED, rdFingerprintGenerator, rdMolDescriptors

from .config import FP_BITS, FP_RADIUS, SCAFFOLD_FP_BITS

# --------------------------------------------------------------------------
# Fingerprints
# --------------------------------------------------------------------------
_morgan = rdFingerprintGenerator.GetMorganGenerator(radius=FP_RADIUS, fpSize=FP_BITS)
_morgan_scaffold = rdFingerprintGenerator.GetMorganGenerator(
    radius=FP_RADIUS, fpSize=SCAFFOLD_FP_BITS
)


def ecfp4_bitvect(mol: Chem.Mol) -> DataStructs.ExplicitBitVect:
    """Binary ECFP4 - used for Tanimoto similarity and the applicability domain."""
    return _morgan.GetFingerprint(mol)


def ecfp4_counts(mol: Chem.Mol) -> np.ndarray:
    """Count-based ECFP4 folded to FP_BITS - the baseline model's main features."""
    fp = _morgan.GetCountFingerprint(mol)
    arr = np.zeros(FP_BITS, dtype=np.float32)
    for idx, count in fp.GetNonzeroElements().items():
        arr[idx] = count
    return arr


def scaffold_fingerprint(mol: Chem.Mol) -> np.ndarray:
    arr = np.zeros(SCAFFOLD_FP_BITS, dtype=np.float32)
    fp = _morgan_scaffold.GetCountFingerprint(mol)
    for idx, count in fp.GetNonzeroElements().items():
        arr[idx] = count
    return arr


def morgan_bit_atoms(mol: Chem.Mol) -> dict[int, set[int]]:
    """
    Map every switched-on ECFP bit to the set of atoms that produced it.
    Used by explain.py to push a bit-level attribution back onto atoms.
    """
    ao = rdFingerprintGenerator.AdditionalOutput()
    ao.AllocateBitInfoMap()
    _morgan.GetFingerprint(mol, additionalOutput=ao)
    out: dict[int, set[int]] = {}
    for bit, envs in ao.GetBitInfoMap().items():
        atoms: set[int] = set()
        for centre, radius in envs:
            if radius == 0:
                atoms.add(centre)
            else:
                env = Chem.FindAtomEnvironmentOfRadiusN(mol, radius, centre)
                for bond_idx in env:
                    bond = mol.GetBondWithIdx(bond_idx)
                    atoms.add(bond.GetBeginAtomIdx())
                    atoms.add(bond.GetEndAtomIdx())
                atoms.add(centre)
        out[int(bit)] = atoms
    return out


# --------------------------------------------------------------------------
# Physicochemical descriptors
# --------------------------------------------------------------------------
DESCRIPTOR_NAMES: tuple[str, ...] = (
    "MolWt", "MolLogP", "MolMR", "TPSA", "NumHAcceptors", "NumHDonors",
    "NumRotatableBonds", "RingCount", "NumAromaticRings", "NumAliphaticRings",
    "NumSaturatedRings", "HeavyAtomCount", "FractionCSP3", "NHOHCount", "NOCount",
    "NumHeteroatoms", "BertzCT", "BalabanJ", "Chi0v", "Chi1v", "Chi2v",
    "Kappa1", "Kappa2", "Kappa3", "HallKierAlpha", "LabuteASA", "qed",
    "NumAmideBonds", "NumSpiroAtoms", "NumBridgeheadAtoms", "FormalCharge",
    "MaxPartialCharge", "MinPartialCharge",
)


def descriptors(mol: Chem.Mol) -> np.ndarray:
    """A compact, numerically stable descriptor block (no exotic 3D terms)."""
    try:
        max_pc = Descriptors.MaxPartialCharge(mol)
        min_pc = Descriptors.MinPartialCharge(mol)
    except Exception:  # noqa: BLE001
        max_pc = min_pc = 0.0
    vals = [
        Descriptors.MolWt(mol),
        Crippen.MolLogP(mol),
        Crippen.MolMR(mol),
        rdMolDescriptors.CalcTPSA(mol),
        rdMolDescriptors.CalcNumHBA(mol),
        rdMolDescriptors.CalcNumHBD(mol),
        rdMolDescriptors.CalcNumRotatableBonds(mol),
        rdMolDescriptors.CalcNumRings(mol),
        rdMolDescriptors.CalcNumAromaticRings(mol),
        rdMolDescriptors.CalcNumAliphaticRings(mol),
        rdMolDescriptors.CalcNumSaturatedRings(mol),
        mol.GetNumHeavyAtoms(),
        rdMolDescriptors.CalcFractionCSP3(mol),
        Descriptors.NHOHCount(mol),
        Descriptors.NOCount(mol),
        rdMolDescriptors.CalcNumHeteroatoms(mol),
        Descriptors.BertzCT(mol),
        Descriptors.BalabanJ(mol),
        Descriptors.Chi0v(mol),
        Descriptors.Chi1v(mol),
        Descriptors.Chi2v(mol),
        Descriptors.Kappa1(mol),
        Descriptors.Kappa2(mol),
        Descriptors.Kappa3(mol),
        Descriptors.HallKierAlpha(mol),
        Descriptors.LabuteASA(mol),
        QED.qed(mol),
        rdMolDescriptors.CalcNumAmideBonds(mol),
        rdMolDescriptors.CalcNumSpiroAtoms(mol),
        rdMolDescriptors.CalcNumBridgeheadAtoms(mol),
        Chem.GetFormalCharge(mol),
        max_pc,
        min_pc,
    ]
    arr = np.asarray(vals, dtype=np.float32)
    return np.nan_to_num(arr, nan=0.0, posinf=0.0, neginf=0.0)


def featurize(mol: Chem.Mol) -> np.ndarray:
    """The full baseline feature vector: ECFP4 counts followed by descriptors."""
    return np.concatenate([ecfp4_counts(mol), descriptors(mol)])


FEATURE_DIM = FP_BITS + len(DESCRIPTOR_NAMES)


def featurize_many(mols: list[Chem.Mol]) -> np.ndarray:
    out = np.zeros((len(mols), FEATURE_DIM), dtype=np.float32)
    for i, m in enumerate(mols):
        out[i] = featurize(m)
    return out


# --------------------------------------------------------------------------
# Graph featurisation for the D-MPNN
# --------------------------------------------------------------------------
ATOM_SYMBOLS = ("C", "N", "O", "S", "F", "Cl", "Br", "I", "P", "B", "Se", "Si", "other")
HYBRIDIZATIONS = (
    Chem.HybridizationType.SP,
    Chem.HybridizationType.SP2,
    Chem.HybridizationType.SP3,
    Chem.HybridizationType.SP3D,
    Chem.HybridizationType.SP3D2,
)
BOND_TYPES = (
    Chem.BondType.SINGLE,
    Chem.BondType.DOUBLE,
    Chem.BondType.TRIPLE,
    Chem.BondType.AROMATIC,
)


def _onehot(value, choices) -> list[float]:
    vec = [0.0] * len(choices)
    try:
        vec[choices.index(value)] = 1.0
    except ValueError:
        vec[-1] = 1.0
    return vec


def atom_features(atom: Chem.Atom) -> list[float]:
    sym = atom.GetSymbol()
    return (
        _onehot(sym if sym in ATOM_SYMBOLS else "other", ATOM_SYMBOLS)
        + _onehot(atom.GetDegree(), [0, 1, 2, 3, 4, 5])
        + _onehot(atom.GetFormalCharge(), [-2, -1, 0, 1, 2])
        + _onehot(atom.GetTotalNumHs(), [0, 1, 2, 3, 4])
        + _onehot(atom.GetHybridization(), list(HYBRIDIZATIONS))
        + [
            float(atom.GetIsAromatic()),
            float(atom.IsInRing()),
            float(atom.IsInRingSize(3)),
            float(atom.IsInRingSize(4)),
            float(atom.IsInRingSize(5)),
            float(atom.IsInRingSize(6)),
            float(atom.IsInRingSize(7)),
            atom.GetMass() * 0.01,
        ]
    )


def bond_features(bond: Chem.Bond) -> list[float]:
    return _onehot(bond.GetBondType(), list(BOND_TYPES)) + [
        float(bond.GetIsConjugated()),
        float(bond.IsInRing()),
        float(bond.GetStereo() != Chem.BondStereo.STEREONONE),
    ]


ATOM_FDIM = len(atom_features(Chem.MolFromSmiles("C").GetAtomWithIdx(0)))
BOND_FDIM = len(bond_features(Chem.MolFromSmiles("CC").GetBondWithIdx(0)))


class MolGraph:
    """
    Directed-edge molecular graph in the form the D-MPNN expects.

    Every bond becomes two directed edges. `rev_index` points each edge at its
    reverse partner so the message update can subtract the incoming message and
    avoid sending information straight back where it came from.
    """

    __slots__ = ("n_atoms", "n_edges", "f_atoms", "f_edges", "edge_src",
                 "edge_dst", "rev_index", "atom_in_edges")

    def __init__(self, mol: Chem.Mol) -> None:
        self.n_atoms = mol.GetNumAtoms()
        self.f_atoms = np.asarray([atom_features(a) for a in mol.GetAtoms()], dtype=np.float32)

        f_edges: list[list[float]] = []
        edge_src: list[int] = []
        edge_dst: list[int] = []
        rev_index: list[int] = []
        atom_in_edges: list[list[int]] = [[] for _ in range(self.n_atoms)]

        for bond in mol.GetBonds():
            a, b = bond.GetBeginAtomIdx(), bond.GetEndAtomIdx()
            bf = bond_features(bond)
            e1 = len(f_edges)
            f_edges.append(list(self.f_atoms[a]) + bf)
            edge_src.append(a)
            edge_dst.append(b)
            atom_in_edges[b].append(e1)

            e2 = len(f_edges)
            f_edges.append(list(self.f_atoms[b]) + bf)
            edge_src.append(b)
            edge_dst.append(a)
            atom_in_edges[a].append(e2)

            rev_index.extend([e2, e1])

        self.n_edges = len(f_edges)
        self.f_edges = (
            np.asarray(f_edges, dtype=np.float32)
            if f_edges
            else np.zeros((0, ATOM_FDIM + BOND_FDIM), dtype=np.float32)
        )
        self.edge_src = np.asarray(edge_src, dtype=np.int64)
        self.edge_dst = np.asarray(edge_dst, dtype=np.int64)
        self.rev_index = np.asarray(rev_index, dtype=np.int64)
        self.atom_in_edges = atom_in_edges
