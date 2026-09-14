"""
Functional-group and privileged-scaffold SMARTS library (Module 1, "deep search").

The sketch asked for a model that "checks the database to see if these
functional groups are present, then which potent activity it can show". That is
two things, and they are kept separate here:

  * detection      - pure substructure matching, done in this module;
  * interpretation - which activity a group is historically tied to, which is
                     computed from the training data by `fg_enrichment.py`
                     rather than hard-coded from folklore.
"""
from __future__ import annotations

from dataclasses import dataclass

from rdkit import Chem


@dataclass(frozen=True)
class GroupDef:
    key: str
    name: str
    smarts: str
    kind: str  # 'functional_group' | 'scaffold' | 'pharmacophore' | 'alert'


_RAW: tuple[tuple[str, str, str, str], ...] = (
    # ---------------- functional groups ----------------
    ("carboxylic_acid", "Carboxylic acid", "[CX3](=O)[OX2H1]", "functional_group"),
    ("carboxylate", "Carboxylate anion", "[CX3](=O)[OX1-]", "functional_group"),
    ("ester", "Ester", "[CX3](=O)[OX2H0][#6]", "functional_group"),
    ("primary_amide", "Primary amide", "[CX3](=O)[NX3H2]", "functional_group"),
    ("secondary_amide", "Secondary amide", "[CX3](=O)[NX3H1][#6]", "functional_group"),
    ("tertiary_amide", "Tertiary amide", "[CX3](=O)[NX3H0]([#6])[#6]", "functional_group"),
    ("sulfonamide", "Sulfonamide", "[SX4](=O)(=O)[NX3]", "functional_group"),
    ("sulfone", "Sulfone", "[#6][SX4](=O)(=O)[#6]", "functional_group"),
    ("sulfoxide", "Sulfoxide", "[#6][SX3](=O)[#6]", "functional_group"),
    ("sulfonic_acid", "Sulfonic acid", "[SX4](=O)(=O)[OX2H1]", "functional_group"),
    ("primary_amine", "Primary amine", "[NX3;H2;!$(NC=[O,S,N]);!$(N[S,P]=O)]", "functional_group"),
    ("secondary_amine", "Secondary amine", "[NX3;H1;!$(NC=[O,S,N]);!$(N[S,P]=O);!R]", "functional_group"),
    ("tertiary_amine", "Tertiary amine", "[NX3;H0;!$(NC=[O,S,N]);!$(N[S,P]=O);!$(N=*);!R]", "functional_group"),
    ("aniline", "Aniline (aryl amine)", "[NX3;H2,H1,H0][c]", "functional_group"),
    ("quaternary_ammonium", "Quaternary ammonium", "[NX4+]", "functional_group"),
    ("guanidine", "Guanidine", "[NX3][CX3](=[NX2,NX3+])[NX3]", "pharmacophore"),
    ("amidine", "Amidine", "[CX3](=[NX2])[NX3]", "pharmacophore"),
    ("urea", "Urea", "[NX3][CX3](=O)[NX3]", "pharmacophore"),
    ("thiourea", "Thiourea", "[NX3][CX3](=S)[NX3]", "pharmacophore"),
    ("carbamate", "Carbamate", "[NX3][CX3](=O)[OX2][#6]", "functional_group"),
    ("phenol", "Phenol", "[OX2H][c]", "functional_group"),
    ("catechol", "Catechol", "[OX2H]c1ccccc1[OX2H]", "pharmacophore"),
    ("aliphatic_hydroxyl", "Aliphatic hydroxyl", "[OX2H][CX4]", "functional_group"),
    ("ether", "Ether", "[OD2]([#6])[#6]", "functional_group"),
    ("methoxy", "Methoxy", "[OX2][CH3]", "functional_group"),
    ("aldehyde", "Aldehyde", "[CX3H1](=O)[#6]", "functional_group"),
    ("ketone", "Ketone", "[#6][CX3](=O)[#6]", "functional_group"),
    ("nitro", "Nitro", "[$([NX3](=O)=O),$([NX3+](=O)[O-])]", "functional_group"),
    ("nitrile", "Nitrile", "[NX1]#[CX2]", "functional_group"),
    ("halogen_aryl", "Aryl halide", "[F,Cl,Br,I][c]", "functional_group"),
    ("trifluoromethyl", "Trifluoromethyl", "[CX4](F)(F)F", "functional_group"),
    ("thiol", "Thiol", "[SX2H]", "functional_group"),
    ("thioether", "Thioether", "[#6][SX2][#6]", "functional_group"),
    ("hydrazone", "Hydrazone", "[NX3][NX2]=[CX3]", "pharmacophore"),
    ("hydrazide", "Hydrazide", "[CX3](=O)[NX3][NX3]", "pharmacophore"),
    ("oxime", "Oxime", "[CX3]=[NX2][OX2H]", "functional_group"),
    ("imine", "Imine (Schiff base)", "[CX3]=[NX2][#6]", "pharmacophore"),
    ("enone", "Alpha,beta-unsaturated ketone", "[CX3]=[CX3][CX3]=O", "pharmacophore"),
    ("phosphate", "Phosphate / phosphonate", "[PX4](=O)([OX2])[OX2]", "functional_group"),
    ("boronic_acid", "Boronic acid", "[BX3]([OX2H])[OX2H]", "functional_group"),
    ("azide", "Azide", "[NX2]=[NX2+]=[NX1-]", "functional_group"),

    # ---------------- privileged ring systems ----------------
    ("benzene", "Benzene ring", "c1ccccc1", "scaffold"),
    ("pyridine", "Pyridine", "c1ccncc1", "scaffold"),
    ("pyrimidine", "Pyrimidine", "c1cncnc1", "scaffold"),
    ("pyrazine", "Pyrazine", "c1cnccn1", "scaffold"),
    ("pyridazine", "Pyridazine", "c1ccnnc1", "scaffold"),
    ("triazine", "1,3,5-Triazine", "c1ncncn1", "scaffold"),
    ("pyrrole", "Pyrrole", "c1cc[nX3]c1", "scaffold"),
    ("imidazole", "Imidazole", "c1cnc[nX3]1", "scaffold"),
    ("pyrazole", "Pyrazole", "c1cn[nX3]c1", "scaffold"),
    ("triazole", "1,2,4-Triazole", "c1nc[nX3]n1", "scaffold"),
    ("tetrazole", "Tetrazole", "c1nnn[nX3]1", "pharmacophore"),
    ("thiazole", "Thiazole", "c1cscn1", "scaffold"),
    ("oxazole", "Oxazole", "c1cocn1", "scaffold"),
    ("isoxazole", "Isoxazole", "c1ccno1", "scaffold"),
    ("thiadiazole", "1,3,4-Thiadiazole", "c1nncs1", "scaffold"),
    ("oxadiazole", "1,3,4-Oxadiazole", "c1nnco1", "scaffold"),
    ("furan", "Furan", "c1ccoc1", "scaffold"),
    ("thiophene", "Thiophene", "c1ccsc1", "scaffold"),
    ("indole", "Indole", "c1ccc2[nX3]ccc2c1", "scaffold"),
    ("indazole", "Indazole", "c1ccc2[nX3]ncc2c1", "scaffold"),
    ("benzimidazole", "Benzimidazole", "c1ccc2[nX3]cnc2c1", "scaffold"),
    ("benzothiazole", "Benzothiazole", "c1ccc2scnc2c1", "scaffold"),
    ("benzoxazole", "Benzoxazole", "c1ccc2ocnc2c1", "scaffold"),
    ("benzofuran", "Benzofuran", "c1ccc2occc2c1", "scaffold"),
    ("quinoline", "Quinoline", "c1ccc2ncccc2c1", "scaffold"),
    ("isoquinoline", "Isoquinoline", "c1ccc2cnccc2c1", "scaffold"),
    ("quinazoline", "Quinazoline", "c1ccc2ncncc2c1", "scaffold"),
    ("quinoxaline", "Quinoxaline", "c1ccc2nccnc2c1", "scaffold"),
    ("purine", "Purine", "c1ncc2[nX3]cnc2n1", "scaffold"),
    ("pteridine", "Pteridine", "c1cnc2nccnc2n1", "scaffold"),
    ("acridine", "Acridine", "c1ccc2nc3ccccc3cc2c1", "scaffold"),
    ("carbazole", "Carbazole", "c1ccc2c(c1)[nX3]c1ccccc12", "scaffold"),
    ("coumarin", "Coumarin", "O=c1ccc2ccccc2o1", "scaffold"),
    ("chromone", "Chromone", "O=c1ccoc2ccccc12", "scaffold"),
    ("flavone", "Flavone", "O=c1cc(-c2ccccc2)oc2ccccc12", "scaffold"),
    ("chalcone", "Chalcone", "O=C(C=Cc1ccccc1)c1ccccc1", "scaffold"),
    ("piperidine", "Piperidine", "C1CCNCC1", "scaffold"),
    ("piperazine", "Piperazine", "C1CNCCN1", "scaffold"),
    ("morpholine", "Morpholine", "C1COCCN1", "scaffold"),
    ("pyrrolidine", "Pyrrolidine", "C1CCNC1", "scaffold"),
    ("azetidine", "Azetidine", "C1CNC1", "scaffold"),
    ("thiomorpholine", "Thiomorpholine", "C1CSCCN1", "scaffold"),
    ("tetrahydrofuran", "Tetrahydrofuran", "C1CCOC1", "scaffold"),
    ("beta_lactam", "Beta-lactam", "O=C1CCN1", "pharmacophore"),
    ("hydantoin", "Hydantoin", "O=C1NC(=O)CN1", "scaffold"),
    ("thiazolidinedione", "Thiazolidine-2,4-dione", "O=C1CSC(=O)N1", "pharmacophore"),
    ("barbiturate", "Barbiturate", "O=C1NC(=O)NC(=O)C1", "scaffold"),
    ("pyrimidinone", "Pyrimidin-4(3H)-one", "O=c1cc[nX3]cn1", "scaffold"),
    ("steroid_core", "Steroid nucleus",
     "[#6]1~[#6]~[#6]~[#6]2~[#6](~[#6]~1)~[#6]~[#6]~[#6]1~[#6]3~[#6]~[#6]~[#6]~[#6]~3~[#6]~[#6]~[#6]~2~1",
     "scaffold"),
    ("sulfonylurea", "Sulfonylurea", "[SX4](=O)(=O)[NX3][CX3](=O)[NX3]", "pharmacophore"),
    ("biphenyl", "Biphenyl", "c1ccc(-c2ccccc2)cc1", "scaffold"),
    ("diaryl_ether", "Diaryl ether", "c1ccc(Oc2ccccc2)cc1", "scaffold"),
    ("benzenesulfonamide", "Benzenesulfonamide", "[NX3][SX4](=O)(=O)c1ccccc1", "pharmacophore"),
    ("anilide", "Anilide", "[CX3](=O)[NX3H1]c1ccccc1", "pharmacophore"),

    # ---------------- structural alerts ----------------
    ("michael_acceptor", "Michael acceptor", "[CX3]=[CX3][CX3](=O)[#6,#7,#8]", "alert"),
    ("epoxide", "Epoxide", "C1OC1", "alert"),
    ("aziridine", "Aziridine", "C1NC1", "alert"),
    ("acyl_halide", "Acyl halide", "[CX3](=O)[F,Cl,Br,I]", "alert"),
    ("aldehyde_alert", "Reactive aldehyde", "[CX3H1](=O)", "alert"),
    ("nitroso", "Nitroso", "[NX2]=O", "alert"),
    ("azo", "Azo", "[#6][NX2]=[NX2][#6]", "alert"),
    ("quinone", "Quinone", "O=C1C=CC(=O)C=C1", "alert"),
    ("thiocyanate", "Isothiocyanate", "[NX2]=[CX2]=[SX1]", "alert"),
)

GROUPS: tuple[GroupDef, ...] = tuple(GroupDef(k, n, s, t) for k, n, s, t in _RAW)
GROUP_BY_KEY: dict[str, GroupDef] = {g.key: g for g in GROUPS}
GROUP_KEYS: tuple[str, ...] = tuple(g.key for g in GROUPS)

# Compile once; a bad SMARTS would silently match nothing, so drop it loudly.
_PATTERNS: dict[str, Chem.Mol] = {}
for _g in GROUPS:
    _p = Chem.MolFromSmarts(_g.smarts)
    if _p is None:
        raise ValueError(f"Invalid SMARTS for group {_g.key!r}: {_g.smarts}")
    _PATTERNS[_g.key] = _p


def match_groups(mol: Chem.Mol) -> dict[str, list[tuple[int, ...]]]:
    """Return every group present, mapped to the atom indices of each match."""
    hits: dict[str, list[tuple[int, ...]]] = {}
    for key, patt in _PATTERNS.items():
        matches = mol.GetSubstructMatches(patt, uniquify=True)
        if matches:
            hits[key] = [tuple(m) for m in matches]
    return hits


def group_vector(mol: Chem.Mol) -> list[int]:
    """Binary presence vector over GROUP_KEYS - a cheap interpretable feature block."""
    return [1 if mol.HasSubstructMatch(_PATTERNS[k]) else 0 for k in GROUP_KEYS]


def describe_groups(mol: Chem.Mol) -> list[dict]:
    """Human-readable inventory used in the report and the API response."""
    out = []
    for key, matches in match_groups(mol).items():
        g = GROUP_BY_KEY[key]
        out.append({
            "key": g.key,
            "name": g.name,
            "kind": g.kind,
            "count": len(matches),
            "atoms": sorted({a for m in matches for a in m}),
        })
    return sorted(out, key=lambda d: (d["kind"], -d["count"], d["name"]))
