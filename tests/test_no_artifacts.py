"""
Tests that run without the trained bundle or the curated data.

`scripts/smoke_test.py` exercises the full stack, but it needs a 69 MB model
bundle that is not in the repository, so it cannot run in CI. Everything here
depends only on source and RDKit, which makes it the part a pull request can
actually be gated on.

The SMARTS checks are not busywork. Four malformed patterns have shipped into
this file's subjects during development - a comma inside a nitro group, a broken
isoxazole, a steroid core and a chalcone stereo-specification - and each was
found by compiling the table rather than by noticing a wrong answer downstream.
A pattern that fails to compile does not raise; RDKit returns None and the group
silently never matches anything.

    python -m pytest tests/ -q
"""
from __future__ import annotations

import pytest

from rdkit import Chem, RDLogger

RDLogger.DisableLog("rdApp.*")

# A few well-known drugs. Nothing here depends on a trained model - these are
# structural assertions, not predictions.
ASPIRIN = "CC(=O)Oc1ccccc1C(=O)O"
IMATINIB = "Cc1ccc(NC(=O)c2ccc(CN3CCN(C)CC3)cc2)cc1Nc1nccc(-c2cccnc2)n1"
FLUCONAZOLE = "OC(Cn1cncn1)(Cn1cncn1)c1ccc(F)cc1F"
CAFFEINE = "Cn1c(=O)c2c(ncn2C)n(C)c1=O"


# --------------------------------------------------------------- SMARTS tables

def test_functional_group_smarts_all_compile():
    from chemrob.fgroups import GROUPS

    bad = [(g.key, g.smarts) for g in GROUPS if Chem.MolFromSmarts(g.smarts) is None]
    assert not bad, f"SMARTS failed to compile: {bad}"


def test_functional_group_keys_unique():
    from chemrob.fgroups import GROUPS

    keys = [g.key for g in GROUPS]
    assert len(keys) == len(set(keys)), "duplicate functional-group keys"


def test_covalent_warhead_smarts_all_compile():
    from chemrob.covalent import WARHEADS

    bad = [(w.key, w.smarts) for w in WARHEADS if Chem.MolFromSmarts(w.smarts) is None]
    assert not bad, f"warhead SMARTS failed to compile: {bad}"


def test_bioisostere_replacements_are_valid_structures():
    from chemrob.bioisosteres import RING_ISOSTERES, SUBSTITUENT_ISOSTERES

    for name, table in (("ring", RING_ISOSTERES), ("substituent", SUBSTITUENT_ISOSTERES)):
        for entry in table:
            for smi in entry if isinstance(entry, (list, tuple)) else [entry]:
                if isinstance(smi, str) and not smi.startswith("["):
                    assert (Chem.MolFromSmiles(smi) is not None
                            or Chem.MolFromSmarts(smi) is not None), \
                        f"{name} isostere is neither valid SMILES nor SMARTS: {smi!r}"


# ------------------------------------------------------------- config coherence

def test_class_and_target_keys_are_consistent():
    from chemrob.config import (ACTIVITY_CLASSES, CLASS_KEYS, LIABILITY_CLASS_KEYS,
                                TARGET_KEYS, THERAPEUTIC_CLASS_KEYS)

    assert len(CLASS_KEYS) == len(set(CLASS_KEYS)), "duplicate class keys"
    assert len(TARGET_KEYS) == len(set(TARGET_KEYS)), "duplicate target keys"
    # The two blocks must partition the heads - the whole reporting story depends
    # on never averaging across them, which needs them to be disjoint and total.
    assert set(THERAPEUTIC_CLASS_KEYS) | set(LIABILITY_CLASS_KEYS) == set(CLASS_KEYS)
    assert not (set(THERAPEUTIC_CLASS_KEYS) & set(LIABILITY_CLASS_KEYS))
    assert len(ACTIVITY_CLASSES) == len(CLASS_KEYS)


def test_every_class_has_targets_and_ordered_thresholds():
    from chemrob.config import ACTIVITY_CLASSES

    for c in ACTIVITY_CLASSES:
        assert c.targets, f"{c.key} has no targets"
        assert c.active_threshold > c.inactive_threshold, (
            f"{c.key}: active {c.active_threshold} must exceed "
            f"inactive {c.inactive_threshold}, or the masked band is inverted")


def test_target_to_class_maps_into_known_classes():
    from chemrob.config import CLASS_KEYS, TARGET_TO_CLASS

    unknown = {t: k for t, k in TARGET_TO_CLASS.items() if k not in CLASS_KEYS}
    assert not unknown, f"targets mapped to classes that do not exist: {unknown}"


# ------------------------------------------------------------- standardization

@pytest.mark.parametrize("smiles", [ASPIRIN, IMATINIB, FLUCONAZOLE, CAFFEINE])
def test_standardization_is_idempotent(smiles):
    from chemrob.standardize import standardize_smiles

    first = standardize_smiles(smiles)
    assert first.ok, f"failed to standardize {smiles}: {first}"
    second = standardize_smiles(first.smiles)
    assert second.ok and second.smiles == first.smiles, \
        "standardizing twice changed the structure"


def test_salt_is_stripped_to_the_parent():
    from chemrob.standardize import standardize_smiles

    # Aspirin sodium -> aspirin parent; the counter-ion must not survive.
    # Not sodium acetate: that parent is 4 heavy atoms and is correctly rejected
    # by the size floor, which would make this test pass for the wrong reason.
    res = standardize_smiles("CC(=O)Oc1ccccc1C(=O)[O-].[Na+]")
    assert res.ok, f"aspirin sodium should standardize: {res.reason}"
    assert "Na" not in res.smiles and "." not in res.smiles


def test_junk_input_is_rejected_not_raised():
    from chemrob.standardize import standardize_smiles

    for junk in ("not a molecule", "", "C(C(C"):
        res = standardize_smiles(junk)
        assert not res.ok, f"{junk!r} should not standardize"


def test_heavy_atom_bounds_are_enforced():
    from chemrob.standardize import standardize_smiles

    assert not standardize_smiles("CCO").ok, "3 heavy atoms is below the floor"


# ---------------------------------------------------------------- featurization

def test_feature_vector_has_the_declared_width():
    from chemrob.featurize import DESCRIPTOR_NAMES, FEATURE_DIM, featurize
    from chemrob.config import FP_BITS

    assert FEATURE_DIM == FP_BITS + len(DESCRIPTOR_NAMES)
    x = featurize(Chem.MolFromSmiles(IMATINIB))
    assert x.shape == (FEATURE_DIM,), f"expected {FEATURE_DIM} features, got {x.shape}"


def test_featurization_is_deterministic_and_finite():
    import numpy as np
    from chemrob.featurize import featurize

    mol = Chem.MolFromSmiles(FLUCONAZOLE)
    a, b = featurize(mol), featurize(mol)
    assert np.array_equal(a, b), "featurizer is not deterministic"
    assert np.isfinite(a).all(), "featurizer produced NaN or inf"


def test_different_molecules_give_different_features():
    import numpy as np
    from chemrob.featurize import featurize

    a = featurize(Chem.MolFromSmiles(ASPIRIN))
    b = featurize(Chem.MolFromSmiles(IMATINIB))
    assert not np.array_equal(a, b)


# --------------------------------------------------------------------- scaffold

def test_scaffold_strips_substituents_but_keeps_the_core():
    from chemrob.scaffold import murcko_scaffold, scaffold_from_smiles

    # murcko_scaffold takes a Mol; scaffold_from_smiles is the string entry point.
    # Passing a string to the former returns "" rather than raising, so a test
    # that got this wrong would look like a scaffolding bug.
    assert murcko_scaffold(Chem.MolFromSmiles(ASPIRIN)) == "c1ccccc1"
    core = scaffold_from_smiles(IMATINIB)
    assert core and Chem.MolFromSmiles(core) is not None
    assert Chem.MolFromSmiles(core).GetNumAtoms() < Chem.MolFromSmiles(IMATINIB).GetNumAtoms()


# ------------------------------------------------------------------ evaluation

def test_block_summary_never_mixes_the_two_head_groups():
    import pandas as pd
    from chemrob.evaluate import summarize_by_block
    from chemrob.config import LIABILITY_CLASS_KEYS, THERAPEUTIC_CLASS_KEYS

    frame = pd.DataFrame([
        {"task": THERAPEUTIC_CLASS_KEYS[0], "auroc": 0.9, "auprc": 0.9,
         "auprc_lift": 2.0, "positive_rate": 0.4, "n": 100, "f1": 0.8, "mcc": 0.7},
        {"task": LIABILITY_CLASS_KEYS[0], "auroc": 0.8, "auprc": 0.95,
         "auprc_lift": 1.1, "positive_rate": 0.87, "n": 100, "f1": 0.9, "mcc": 0.3},
    ])
    out = summarize_by_block(frame)
    assert set(out) == {"therapeutic", "liability"}
    assert out["therapeutic"]["n_tasks_evaluated"] == 1
    assert out["liability"]["n_tasks_evaluated"] == 1
    # The pooled mean would be 0.925; neither block may report it.
    assert out["therapeutic"]["macro_auprc"] != out["liability"]["macro_auprc"]
