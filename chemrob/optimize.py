"""
Layer 5 - Optimization suggestion engine (Module 3).

Answers the question the original sketch ends on: "what changes need to be made
in the ring / pharmacophore for the desired activity?"

The action space is deliberately precedented rather than free-form:

  1. mined matched-pair transformations  - swaps measured to help in this dataset
  2. curated bioisosteres                - standard medicinal-chemistry moves
  3. ring replacement                    - scaffold hopping across equivalent cores

Every candidate is then re-scored by the activity model, filtered for synthetic
accessibility, drug-likeness and structural alerts, checked for novelty against
the training set, and ranked by a composite score. Applying this repeatedly is
the iterative loop from the workflow diagram: each round's winners become the
next round's seeds.

Scope note: this is a reward-guided search over a discrete, precedented action
space, not a trained graph generator. It uses the activity model as the reward
signal in the same way an RL fine-tuned generator would, but the policy is the
rule library rather than learned weights. The deck flags the learned generator
as the riskiest component and recommends prototyping it first; this module is
that prototype, and the rule library is the part a learned policy would replace.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

import numpy as np
import pandas as pd
from rdkit import Chem, DataStructs

from .bioisosteres import RING_ISOSTERES, isosteres_for
from .config import CLASS_BY_KEY, CLASS_KEYS, THERAPEUTIC_CLASS_KEYS
from .featurize import ecfp4_bitvect
from .mmp import apply_transformation, rules_for_substituent, single_cut_fragments
from .properties import passes_filters, profile, sa_score
from .standardize import standardize_smiles

log = logging.getLogger(__name__)


@dataclass
class Candidate:
    smiles: str
    parent_smiles: str
    edit_description: str
    source: str                      # 'mmp' | 'bioisostere' | 'ring_swap'
    precedent: dict = field(default_factory=dict)
    probability: float = float("nan")
    delta_probability: float = float("nan")
    pactivity: float = float("nan")
    delta_pactivity: float = float("nan")
    similarity_to_parent: float = float("nan")
    props: dict = field(default_factory=dict)
    novel: bool = True
    composite_score: float = float("nan")
    in_domain: bool = True

    def to_dict(self) -> dict:
        return {
            "smiles": self.smiles,
            "edit": self.edit_description,
            "source": self.source,
            "precedent": self.precedent,
            "predicted_probability": round(float(self.probability), 4),
            "delta_vs_parent": round(float(self.delta_probability), 4),
            "predicted_pactivity": (
                None if np.isnan(self.pactivity) else round(float(self.pactivity), 2)
            ),
            "delta_pactivity": (
                None if np.isnan(self.delta_pactivity) else round(float(self.delta_pactivity), 2)
            ),
            "similarity_to_parent": round(float(self.similarity_to_parent), 3),
            "novel_vs_training_set": self.novel,
            "in_applicability_domain": self.in_domain,
            "properties": self.props,
            "score": round(float(self.composite_score), 4),
        }


# --------------------------------------------------------------------------
# Candidate generation
# --------------------------------------------------------------------------
def _mmp_candidates(mol: Chem.Mol, rules: pd.DataFrame, class_key: str,
                    max_per_site: int) -> list[Candidate]:
    parent = Chem.MolToSmiles(mol)
    out: list[Candidate] = []
    if rules is None or rules.empty:
        return out

    for core, sub in single_cut_fragments(mol):
        hits = rules_for_substituent(rules, sub, class_key, top_n=max_per_site)
        for _, r in hits.iterrows():
            new_smi = apply_transformation(core, r["to_sub"])
            if not new_smi or new_smi == parent:
                continue
            out.append(Candidate(
                smiles=new_smi,
                parent_smiles=parent,
                edit_description=f"replace {sub} with {r['to_sub']}",
                source="mmp",
                precedent={
                    "n_pairs": int(r["n_pairs"]),
                    "mean_delta_pActivity": round(float(r["mean_delta"]), 3),
                    "fraction_improved": round(float(r["frac_improved"]), 3),
                    "evidence": (
                        f"{int(r['n_pairs'])} matched pairs in the training set show "
                        f"a mean {float(r['mean_delta']):+.2f} log-unit change for "
                        f"{CLASS_BY_KEY[class_key].label.lower()}"
                    ),
                },
            ))
    return out


def _bioisostere_candidates(mol: Chem.Mol, max_per_site: int) -> list[Candidate]:
    parent = Chem.MolToSmiles(mol)
    out: list[Candidate] = []
    for core, sub in single_cut_fragments(mol):
        for repl in isosteres_for(sub)[:max_per_site]:
            new_smi = apply_transformation(core, repl)
            if not new_smi or new_smi == parent:
                continue
            out.append(Candidate(
                smiles=new_smi,
                parent_smiles=parent,
                edit_description=f"bioisosteric swap {sub} -> {repl}",
                source="bioisostere",
                precedent={"evidence": "standard medicinal-chemistry bioisostere"},
            ))
    return out


def _ring_swap_candidates(mol: Chem.Mol) -> list[Candidate]:
    """
    Scaffold hopping: replace a whole ring with an accepted equivalent.

    Ring replacement is what the sketch means by "changes to the core ring", as
    opposed to decorating it. RDKit's ReplaceSubstructs handles the surgery;
    anything that fails to sanitize afterwards is discarded.
    """
    parent = Chem.MolToSmiles(mol)
    parent_heavy = mol.GetNumHeavyAtoms()
    out: list[Candidate] = []
    for family in RING_ISOSTERES:
        for src_smarts, src_smiles in family:
            patt = Chem.MolFromSmarts(src_smarts)
            if patt is None:
                continue
            match = mol.GetSubstructMatch(patt)
            if not match:
                continue
            for dst_smarts, dst_smiles in family:
                if dst_smarts == src_smarts:
                    continue
                repl = Chem.MolFromSmiles(dst_smiles)
                if repl is None:
                    continue
                # ReplaceSubstructs re-attaches the replacement at the first
                # match atom only. Any other substituent on the replaced ring is
                # left dangling as a separate fragment, which standardization
                # would then silently discard - turning what looks like a ring
                # swap into a truncated molecule. Reject anything disconnected,
                # and require the heavy-atom count to move by the ring-size
                # difference and nothing more.
                expected_delta = repl.GetNumHeavyAtoms() - len(match)
                try:
                    products = Chem.ReplaceSubstructs(mol, patt, repl, replaceAll=False)
                except Exception:  # noqa: BLE001
                    continue
                for p in products[:2]:
                    try:
                        Chem.SanitizeMol(p)
                        smi = Chem.MolToSmiles(p)
                    except Exception:  # noqa: BLE001
                        continue
                    if "." in smi:
                        continue
                    if abs((p.GetNumHeavyAtoms() - parent_heavy) - expected_delta) > 1:
                        continue
                    res = standardize_smiles(smi)
                    if not res.ok or res.smiles == parent:
                        continue
                    out.append(Candidate(
                        smiles=res.smiles,
                        parent_smiles=parent,
                        edit_description=f"ring replacement {src_smiles} -> {dst_smiles}",
                        source="ring_swap",
                        precedent={"evidence": "scaffold hop across an accepted ring-equivalence set"},
                    ))
    return out


def _ring_edit_candidates(mol: Chem.Mol, *, max_matches: int = 2) -> list[Candidate]:
    """
    Same-size ring swaps done by mutating atoms in place rather than by
    substructure replacement.

    ReplaceSubstructs can only re-attach one substituent, so it silently fails
    on the multiply-substituted rings that matter most (a trisubstituted pyrazole
    core, say). When source and target rings have the same number of atoms, the
    swap is really just a change of which positions are heteroatoms - so the
    matched atoms are re-elemented in place and every bond and substituent is
    preserved.

    All rotations and both directions of the ring are tried, because there is no
    guaranteed correspondence between where the heteroatoms sit in the two ring
    SMILES. Placements that put a nitrogen where a substituted carbon was will
    fail valence checks during sanitization and are dropped.
    """
    parent = Chem.MolToSmiles(mol)
    out: list[Candidate] = []
    seen: set[str] = set()

    for family in RING_ISOSTERES:
        for src_smarts, src_smiles in family:
            patt = Chem.MolFromSmarts(src_smarts)
            if patt is None:
                continue
            matches = mol.GetSubstructMatches(patt, uniquify=True)[:max_matches]
            if not matches:
                continue
            for dst_smarts, dst_smiles in family:
                if dst_smiles == src_smiles:
                    continue
                dst_mol = Chem.MolFromSmiles(dst_smiles)
                if dst_mol is None or dst_mol.GetNumAtoms() != patt.GetNumAtoms():
                    continue
                dst_z = [a.GetAtomicNum() for a in dst_mol.GetAtoms()]
                n = len(dst_z)

                for match in matches:
                    for offset in range(n):
                        for direction in (1, -1):
                            order = [dst_z[(offset + direction * i) % n] for i in range(n)]
                            smi = _apply_ring_elements(mol, match, order)
                            if smi is None or smi == parent or smi in seen:
                                continue
                            res = standardize_smiles(smi)
                            if not res.ok or res.smiles == parent or res.smiles in seen:
                                continue
                            seen.add(res.smiles)
                            out.append(Candidate(
                                smiles=res.smiles,
                                parent_smiles=parent,
                                edit_description=f"ring edit {src_smiles} -> {dst_smiles}",
                                source="ring_swap",
                                precedent={
                                    "evidence": "scaffold hop across an accepted "
                                                "ring-equivalence set (substituents retained)"
                                },
                            ))
    return out


def _apply_ring_elements(
    mol: Chem.Mol, atom_indices: tuple[int, ...], atomic_numbers: list[int]
) -> str | None:
    """Re-element the matched ring atoms, then sanitize. None if invalid."""
    for explicit_h in (0, 1):
        rw = Chem.RWMol(mol)
        changed = False
        for idx, z in zip(atom_indices, atomic_numbers):
            atom = rw.GetAtomWithIdx(int(idx))
            if atom.GetAtomicNum() == z:
                continue
            atom.SetAtomicNum(z)
            atom.SetNoImplicit(False)
            atom.SetNumExplicitHs(0)
            atom.SetFormalCharge(0)
            changed = True
            # A pyrrole-type nitrogen needs its hydrogen stated explicitly or
            # the ring will not kekulize; a pyridine-type one must not have it.
            # Rather than guess, both are tried.
            if z == 7 and explicit_h and atom.GetDegree() == 2:
                atom.SetNumExplicitHs(1)
        if not changed:
            return None
        try:
            product = rw.GetMol()
            Chem.SanitizeMol(product)
            return Chem.MolToSmiles(product)
        except Exception:  # noqa: BLE001
            continue
    return None


def generate_candidates(
    mol: Chem.Mol,
    rules: pd.DataFrame | None,
    class_key: str,
    *,
    max_per_site: int = 6,
    include_ring_swaps: bool = True,
) -> list[Candidate]:
    cands = _mmp_candidates(mol, rules, class_key, max_per_site)
    cands += _bioisostere_candidates(mol, max_per_site)
    if include_ring_swaps:
        cands += _ring_swap_candidates(mol)
        cands += _ring_edit_candidates(mol)

    seen: set[str] = set()
    unique: list[Candidate] = []
    for c in cands:
        if c.smiles in seen:
            continue
        seen.add(c.smiles)
        unique.append(c)
    log.info("generated %d unique candidates (%d raw)", len(unique), len(cands))
    return unique


# --------------------------------------------------------------------------
# Scoring and ranking
# --------------------------------------------------------------------------
def score_candidates(
    predictor,
    candidates: list[Candidate],
    class_key: str,
    parent_prob: float,
    parent_mol: Chem.Mol,
    *,
    parent_pact: float = float("nan"),
    max_sa: float = 6.0,
    min_qed: float = 0.25,
    allow_alerts: bool = False,
) -> list[Candidate]:
    """Filter, predict, and attach developability and novelty information."""
    j = CLASS_KEYS.index(class_key)
    parent_fp = ecfp4_bitvect(parent_mol)
    ad = getattr(predictor.bundle, "ad", None)
    training = set(ad.smiles) if ad is not None else set()

    kept: list[Candidate] = []
    for c in candidates:
        mol = Chem.MolFromSmiles(c.smiles)
        if mol is None:
            continue
        ok, _reason = passes_filters(mol, max_sa=max_sa, min_qed=min_qed,
                                     allow_alerts=allow_alerts)
        if not ok:
            continue
        c.similarity_to_parent = float(
            DataStructs.TanimotoSimilarity(parent_fp, ecfp4_bitvect(mol))
        )
        c.novel = c.smiles not in training
        c.props = profile(mol)
        kept.append(c)

    if not kept:
        return []

    smis = [c.smiles for c in kept]
    probs = predictor.score_smiles(smis)[:, j]
    pots = predictor.score_smiles_potency(smis)[:, j]
    for c, p, pot in zip(kept, probs, pots):
        c.probability = float(p)
        c.delta_probability = float(p) - parent_prob
        c.pactivity = float(pot)
        if not np.isnan(pot) and not np.isnan(parent_pact):
            c.delta_pactivity = float(pot) - parent_pact
        if ad is not None:
            mol = Chem.MolFromSmiles(c.smiles)
            c.in_domain = ad.assess(mol, n_report=1).verdict != "out_of_domain"

    return [c for c in kept if not np.isnan(c.probability)]


def composite_score(c: Candidate, *, novelty_bonus: float = 0.03) -> float:
    """
    Rank by predicted gain, then discount everything that makes a suggestion
    less useful in practice: hard-to-make, poor drug-likeness, or a molecule so
    far from the parent that it is a different series rather than an edit.
    """
    # Probability is the primary signal: it is bounded, and calibrated on a
    # held-out fold. Predicted potency refines it, but only as a secondary term.
    # The regression heads carry a measured RMSE of 0.91 log units per class and
    # 0.81 per target (MAE 0.66 and 0.57), so a predicted potency change smaller
    # than roughly one log unit is inside the model's own error and must not be
    # allowed to outrank a real change in the probability of activity.
    gain = c.delta_probability
    if not np.isnan(c.delta_pactivity):
        gain += 0.15 * float(np.clip(c.delta_pactivity, -2.0, 2.0))
    sa = c.props.get("sa_score", 3.0)
    sa = 3.0 if sa != sa else sa
    qed = c.props.get("qed", 0.5)

    penalty_sa = 0.04 * max(0.0, sa - 3.5)
    penalty_qed = 0.25 * max(0.0, 0.5 - qed)
    penalty_far = 0.10 * max(0.0, 0.35 - c.similarity_to_parent)
    penalty_domain = 0.0 if c.in_domain else 0.08
    bonus_novel = novelty_bonus if c.novel else 0.0
    bonus_precedent = 0.0
    if c.source == "mmp":
        n = c.precedent.get("n_pairs", 0)
        bonus_precedent = 0.05 * min(1.0, n / 25.0)

    return (
        gain + bonus_novel + bonus_precedent
        - penalty_sa - penalty_qed - penalty_far - penalty_domain
    )


def optimize(
    predictor,
    smiles: str,
    class_key: str,
    *,
    n_suggestions: int = 10,
    rounds: int = 1,
    beam_width: int = 3,
    max_per_site: int = 6,
    include_ring_swaps: bool = True,
    max_sa: float = 6.0,
    min_qed: float = 0.25,
) -> dict:
    """
    Run the optimization loop.

    `rounds > 1` feeds each round's best molecules back in as new parents, which
    is the "optimized analogues re-enter the pipeline" arrow in the workflow
    diagram. Suggestions accumulate across rounds and are ranked together.
    """
    if class_key not in THERAPEUTIC_CLASS_KEYS:
        # Optimising *toward* hERG blockade or CYP inhibition is never the
        # intent, so liabilities are refused rather than silently accepted.
        raise ValueError(
            f"unknown or non-optimisable activity class {class_key!r}; "
            f"expected one of {THERAPEUTIC_CLASS_KEYS}"
        )

    res = standardize_smiles(smiles)
    if not res.ok:
        return {"ok": False, "error": res.reason}
    root_mol, root_smiles = res.mol, res.smiles

    rules = getattr(predictor.bundle, "mmp_rules", None)
    j = CLASS_KEYS.index(class_key)
    root_prob = float(predictor.score_smiles([root_smiles])[0, j])
    root_pact = float(predictor.score_smiles_potency([root_smiles])[0, j])
    if np.isnan(root_prob):
        return {
            "ok": False,
            "error": f"the activity head for '{class_key}' was not trained "
                     "(too few labelled molecules in the dataset)",
        }

    all_candidates: dict[str, Candidate] = {}
    frontier = [(root_smiles, root_mol, root_prob, root_pact)]
    round_log: list[dict] = []

    for r in range(1, rounds + 1):
        produced: list[Candidate] = []
        for parent_smiles, parent_mol, parent_prob, parent_pact in frontier:
            cands = generate_candidates(
                parent_mol, rules, class_key,
                max_per_site=max_per_site, include_ring_swaps=include_ring_swaps,
            )
            cands = [c for c in cands if c.smiles not in all_candidates
                     and c.smiles != root_smiles]
            scored = score_candidates(
                predictor, cands, class_key, parent_prob, parent_mol,
                parent_pact=parent_pact, max_sa=max_sa, min_qed=min_qed,
            )
            for c in scored:
                c.composite_score = composite_score(c)
            produced.extend(scored)

        for c in produced:
            prev = all_candidates.get(c.smiles)
            if prev is None or c.composite_score > prev.composite_score:
                all_candidates[c.smiles] = c

        produced.sort(key=lambda c: -c.composite_score)
        round_log.append({
            "round": r,
            "n_generated": len(produced),
            "best_probability": round(produced[0].probability, 4) if produced else None,
        })
        if not produced:
            break

        frontier = []
        for c in produced[:beam_width]:
            m = Chem.MolFromSmiles(c.smiles)
            if m is not None:
                frontier.append((c.smiles, m, c.probability, c.pactivity))
        if not frontier:
            break

    ranked = sorted(all_candidates.values(), key=lambda c: -c.composite_score)
    top = ranked[:n_suggestions]

    return {
        "ok": True,
        "parent": {
            "smiles": root_smiles,
            "probability": round(root_prob, 4),
            "pactivity": None if np.isnan(root_pact) else round(root_pact, 2),
            "properties": profile(root_mol),
        },
        "objective": {
            "class_key": class_key,
            "class_label": CLASS_BY_KEY[class_key].label,
        },
        "rounds": round_log,
        "n_candidates_evaluated": len(all_candidates),
        "suggestions": [c.to_dict() for c in top],
        "note": (
            "Suggestions are precedent-constrained edits re-scored by the activity "
            "model. Predicted gains are model estimates, not measurements, and "
            "require experimental confirmation."
        ),
    }
