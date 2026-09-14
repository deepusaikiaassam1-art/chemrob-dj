"""
Layer 4 - Explainability.

The deck's requirement: "attention-weight mapping over atoms, shown as a
highlighted pharmacophore". Two attribution routes are provided, because the two
model backends expose different internals:

  * D-MPNN  - the readout attention weights are read straight off the encoder.
  * Baseline - atom occlusion. Every ECFP bit knows which atoms generated it, so
    an atom can be silenced by zeroing its bits, and the drop in predicted
    probability is that atom's contribution. Model-agnostic and, unlike raw
    attention, expressed in the units the user cares about (probability).

Atom scores are then pooled over the matched functional groups so the answer
reads "the benzenesulfonamide drove this call", not "atom 14 mattered".
"""
from __future__ import annotations

import numpy as np
from rdkit import Chem
from rdkit.Chem import Draw
from rdkit.Chem.Draw import rdMolDraw2D

from .featurize import FP_BITS, descriptors, ecfp4_counts, morgan_bit_atoms
from .fgroups import GROUP_BY_KEY, match_groups


def occlusion_attribution(predict_fn, mol: Chem.Mol) -> np.ndarray:
    """
    Per-atom contribution to a single task's predicted probability.

    `predict_fn` takes a (n, FEATURE_DIM) matrix and returns (n,) probabilities.
    Positive score = the atom pushes the prediction towards 'active'.
    """
    n_atoms = mol.GetNumAtoms()
    if n_atoms == 0:
        return np.zeros(0, dtype=np.float32)

    base_fp = ecfp4_counts(mol)
    desc = descriptors(mol)
    bit_atoms = morgan_bit_atoms(mol)

    atom_bits: list[list[int]] = [[] for _ in range(n_atoms)]
    for bit, atoms in bit_atoms.items():
        for a in atoms:
            if 0 <= a < n_atoms:
                atom_bits[a].append(bit % FP_BITS)

    batch = np.zeros((n_atoms + 1, base_fp.shape[0] + desc.shape[0]), dtype=np.float32)
    batch[0] = np.concatenate([base_fp, desc])
    for a in range(n_atoms):
        fp = base_fp.copy()
        for b in atom_bits[a]:
            fp[b] = 0.0
        batch[a + 1] = np.concatenate([fp, desc])

    probs = np.asarray(predict_fn(batch), dtype=np.float32)
    return probs[0] - probs[1:]      # drop when the atom is silenced


def normalize_scores(scores: np.ndarray) -> np.ndarray:
    """Scale to [-1, 1] by the largest absolute contribution, for colouring."""
    if scores.size == 0:
        return scores
    m = float(np.abs(scores).max())
    return scores / m if m > 1e-12 else np.zeros_like(scores)


def group_attribution(mol: Chem.Mol, atom_scores: np.ndarray) -> list[dict]:
    """
    Pool atom scores onto the detected functional groups / ring systems.

    Both the total and the mean are reported: total tells you which group moved
    the prediction most overall, mean tells you which group is most potent per
    atom, and a big benzene ring should not outrank a small sulfonamide purely
    on atom count.
    """
    out: list[dict] = []
    for key, matches in match_groups(mol).items():
        atoms = sorted({a for m in matches for a in m if a < len(atom_scores)})
        if not atoms:
            continue
        vals = atom_scores[atoms]
        g = GROUP_BY_KEY[key]
        out.append({
            "key": key,
            "name": g.name,
            "kind": g.kind,
            "atoms": atoms,
            "total_contribution": float(vals.sum()),
            "mean_contribution": float(vals.mean()),
            "n_matches": len(matches),
        })
    return sorted(out, key=lambda d: -abs(d["total_contribution"]))


def top_substructures(mol: Chem.Mol, atom_scores: np.ndarray, radius: int = 1,
                      top_n: int = 5) -> list[dict]:
    """
    The highest-scoring local environments, returned as SMILES fragments.
    Useful when a driving substructure is not in the curated SMARTS library.
    """
    order = np.argsort(-atom_scores)
    seen: set[str] = set()
    out: list[dict] = []
    for idx in order:
        if len(out) >= top_n or atom_scores[idx] <= 0:
            break
        env = Chem.FindAtomEnvironmentOfRadiusN(mol, radius, int(idx))
        if not env:
            continue
        amap: dict[int, int] = {}
        frag = Chem.PathToSubmol(mol, env, atomMap=amap)
        try:
            smi = Chem.MolToSmiles(frag)
        except Exception:  # noqa: BLE001
            continue
        if not smi or smi in seen:
            continue
        seen.add(smi)
        out.append({
            "fragment": smi,
            "centre_atom": int(idx),
            "score": float(atom_scores[idx]),
        })
    return out


def highlight_svg(mol: Chem.Mol, atom_scores: np.ndarray, *, size=(500, 400)) -> str:
    """
    Molecule depiction with atoms coloured by contribution: red pushes towards
    active, blue away from it, intensity proportional to magnitude.
    """
    scores = normalize_scores(atom_scores)
    highlight_atoms = [int(i) for i in range(mol.GetNumAtoms()) if abs(scores[i]) > 0.05]
    colors: dict[int, tuple[float, float, float]] = {}
    radii: dict[int, float] = {}
    for i in highlight_atoms:
        s = float(scores[i])
        if s > 0:
            colors[i] = (1.0, 1.0 - 0.75 * s, 1.0 - 0.75 * s)   # red
        else:
            colors[i] = (1.0 + 0.75 * s, 1.0 + 0.75 * s, 1.0)   # blue
        radii[i] = 0.3 + 0.3 * abs(s)

    drawer = rdMolDraw2D.MolDraw2DSVG(*size)
    opts = drawer.drawOptions()
    opts.addStereoAnnotation = True
    work = rdMolDraw2D.PrepareMolForDrawing(mol)
    rdMolDraw2D.PrepareAndDrawMolecule(
        drawer, work,
        highlightAtoms=highlight_atoms,
        highlightAtomColors=colors,
        highlightAtomRadii=radii,
    )
    drawer.FinishDrawing()
    return drawer.GetDrawingText()


def plain_svg(mol: Chem.Mol, *, size=(400, 320), legend: str = "") -> str:
    drawer = rdMolDraw2D.MolDraw2DSVG(*size)
    rdMolDraw2D.PrepareAndDrawMolecule(drawer, mol, legend=legend)
    drawer.FinishDrawing()
    return drawer.GetDrawingText()


def explain_prediction(predict_fn, mol: Chem.Mol, *, make_svg: bool = False) -> dict:
    """Full explanation bundle for one task."""
    scores = occlusion_attribution(predict_fn, mol)
    bundle = {
        "atom_scores": [round(float(s), 5) for s in scores],
        "groups": group_attribution(mol, scores),
        "fragments": top_substructures(mol, scores),
    }
    if make_svg:
        bundle["svg"] = highlight_svg(mol, scores)
    return bundle
