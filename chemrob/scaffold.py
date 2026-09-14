"""
Bemis-Murcko scaffold utilities.

Two jobs:
  1. Provide the core ring system that Module 2 (the core-ring classifier)
     predicts on.
  2. Provide the grouping key for the scaffold split, so a molecule in the test
     set never shares its skeleton with one in the training set. Random splits
     inflate QSAR metrics badly; scaffold splits are the honest measurement.
"""
from __future__ import annotations

import random
from collections import defaultdict

from rdkit import Chem
from rdkit.Chem.Scaffolds import MurckoScaffold


def murcko_scaffold(mol: Chem.Mol, *, generic: bool = False) -> str:
    """
    Reduce a molecule to rings + linkers with substituents removed.
    `generic=True` additionally strips atom identity and bond order, giving the
    carbon-skeleton framework (useful for coarse scaffold grouping).
    """
    try:
        core = MurckoScaffold.GetScaffoldForMol(mol)
        if core is None or core.GetNumAtoms() == 0:
            return ""
        if generic:
            core = MurckoScaffold.MakeScaffoldGeneric(core)
        return Chem.MolToSmiles(core, canonical=True)
    except Exception:  # noqa: BLE001
        return ""


def scaffold_from_smiles(smiles: str, *, generic: bool = False) -> str:
    mol = Chem.MolFromSmiles(smiles)
    return murcko_scaffold(mol, generic=generic) if mol is not None else ""


def has_ring_system(smiles: str) -> bool:
    mol = Chem.MolFromSmiles(smiles)
    return mol is not None and mol.GetRingInfo().NumRings() > 0


def scaffold_split(
    smiles_list: list[str],
    frac_train: float = 0.80,
    frac_valid: float = 0.10,
    seed: int = 42,
) -> tuple[list[int], list[int], list[int]]:
    """
    Deterministic scaffold split. Largest scaffold groups go to train first so
    that validation and test end up dominated by rarer, genuinely unseen cores.
    Acyclic molecules are pooled under one empty-scaffold key.
    """
    groups: dict[str, list[int]] = defaultdict(list)
    for idx, smi in enumerate(smiles_list):
        groups[scaffold_from_smiles(smi)].append(idx)

    ordered = sorted(groups.values(), key=lambda g: (-len(g), g[0]))
    rng = random.Random(seed)
    # Shuffle the singleton tail so valid/test are not ordered by dataset order.
    head = [g for g in ordered if len(g) > 1]
    tail = [g for g in ordered if len(g) == 1]
    rng.shuffle(tail)
    ordered = head + tail

    n = len(smiles_list)
    n_train, n_valid = int(frac_train * n), int(frac_valid * n)
    train: list[int] = []
    valid: list[int] = []
    test: list[int] = []
    for group in ordered:
        if len(train) + len(group) <= n_train:
            train.extend(group)
        elif len(valid) + len(group) <= n_valid:
            valid.extend(group)
        else:
            test.extend(group)
    return sorted(train), sorted(valid), sorted(test)
