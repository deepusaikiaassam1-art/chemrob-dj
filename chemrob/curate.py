"""
Data curation and label construction.

Raw ChEMBL rows become a molecule-level table with two sparse label matrices:

  * activity labels - one column per pharmacological class (14 columns);
  * target labels   - one column per individual ChEMBL target (36 columns).

Both use three states: 1 active, 0 inactive, NaN never tested. The NaN state
matters. A molecule assayed against COX-2 and nothing else is not evidence of
antibacterial inactivity, so those cells are masked out of the loss rather than
being filled with zeros. Filling them with zeros is the single most common way
multi-task bioactivity models end up with meaningless, optimistic metrics.
"""
from __future__ import annotations

import logging

import numpy as np
import pandas as pd

from .config import (
    CLASS_BY_KEY,
    CLASS_KEYS,
    DATA_DIR,
    EXACT_RELATIONS,
    INACTIVE_THRESHOLD,
    TARGET_KEYS,
    TARGET_TO_CLASS,
)
from .scaffold import murcko_scaffold
from .standardize import standardize_smiles

log = logging.getLogger(__name__)

# Molar units, convertible to nanomolar directly.
_UNIT_TO_NM = {
    "nM": 1.0, "nm": 1.0,
    "uM": 1e3, "um": 1e3, "µM": 1e3, "μM": 1e3,
    "mM": 1e6, "M": 1e9,
    "pM": 1e-3,
}

# Mass-concentration units, convertible only once the molecular weight is known.
# Whole-organism MIC data is overwhelmingly reported this way - roughly two
# thirds of the organism records here are ug.mL-1 - so dropping them would gut
# the antibacterial and antifungal classes.
# ug/mL == mg/L, so nM = (ug/mL) / MW * 1e6.
_MASS_UNIT_TO_UG_PER_ML = {
    "ug.mL-1": 1.0, "ug ml-1": 1.0, "ug/ml": 1.0, "mg.L-1": 1.0, "mg l-1": 1.0,
    "mg.mL-1": 1e3, "mg/ml": 1e3,
    "ng.mL-1": 1e-3, "ng/ml": 1e-3,
    "g.L-1": 1e3,
}

_BAD_VALIDITY = {
    "Outside typical range",
    "Non standard unit for type",
    "Potential missing data",
    "Potential transcription error",
    "Potential author error",
    "Author confirmed error",
}


def _to_nanomolar(value: float, unit: str, mol_wt: float | None) -> float:
    """Convert one measurement to nM, using molecular weight for mass units."""
    if unit in _UNIT_TO_NM:
        return value * _UNIT_TO_NM[unit]
    factor = _MASS_UNIT_TO_UG_PER_ML.get(unit)
    if factor is not None and mol_wt and mol_wt > 0:
        return (value * factor) / mol_wt * 1e6
    return float("nan")


def compute_p_activity(df: pd.DataFrame, mol_wt: pd.Series | None = None) -> pd.Series:
    """
    pActivity = -log10(molar potency), vectorised over the activity table.

    ChEMBL's own pchembl_value is used where present. It is absent for most
    whole-organism records, which are then recomputed from value + unit -
    including the mass-concentration units that need a molecular weight.
    """
    values = pd.to_numeric(df["standard_value"], errors="coerce").to_numpy(dtype=float)
    units = df["standard_units"].astype("object").to_numpy()
    mw = (
        pd.to_numeric(mol_wt, errors="coerce").to_numpy(dtype=float)
        if mol_wt is not None
        else np.full(len(df), np.nan)
    )

    nm = np.full(len(df), np.nan, dtype=float)
    for i in range(len(df)):
        v = values[i]
        if not np.isfinite(v) or v <= 0:
            continue
        nm[i] = _to_nanomolar(v, units[i], mw[i] if np.isfinite(mw[i]) else None)

    with np.errstate(divide="ignore", invalid="ignore"):
        computed = np.where(nm > 0, 9.0 - np.log10(nm), np.nan)

    reported = pd.to_numeric(df.get("pchembl_value"), errors="coerce").to_numpy(dtype=float)
    return pd.Series(np.where(np.isfinite(reported), reported, computed), index=df.index)


def clean_activities(raw: pd.DataFrame) -> pd.DataFrame:
    """
    First-pass filtering: drop rows with no structure or a known data-quality
    flag, and mark explicit 'Not Active' records.

    Potency is deliberately NOT computed here. Mass-concentration units need a
    molecular weight, which is only available after standardization, so the
    conversion happens in `curate()` once structures are parsed.
    """
    df = raw.copy()
    n0 = len(df)

    df = df[df["canonical_smiles"].notna() & (df["canonical_smiles"].astype(str) != "")]
    log.info("dropped %d rows without a structure", n0 - len(df))

    if "data_validity_comment" in df.columns:
        df = df[~df["data_validity_comment"].isin(_BAD_VALIDITY)]

    # Explicit 'Not Active' comments are real negatives even without a number.
    comment = df.get("activity_comment", pd.Series([None] * len(df), index=df.index))
    df["_explicit_inactive"] = (
        comment.astype(str).str.lower().isin({"not active", "inactive", "no activity"})
    )

    df["relation"] = df.get("standard_relation", pd.Series(["="] * len(df), index=df.index))
    df["relation"] = df["relation"].fillna("=")
    log.info("kept %d / %d rows after quality filtering", len(df), n0)
    return df.reset_index(drop=True)


def apply_potency_filters(df: pd.DataFrame) -> pd.DataFrame:
    """Keep only rows whose pActivity is both computable and usable."""
    n0 = len(df)
    # A '>' record is a lower bound: useless as evidence of potency, but good
    # evidence of inactivity when the bound itself is already weak.
    censored_useful = (df["relation"].isin([">", ">="])) & (
        df["p_activity"] < INACTIVE_THRESHOLD
    )
    exact = df["relation"].isin(EXACT_RELATIONS)
    df = df[exact | censored_useful | df["_explicit_inactive"]]

    df = df[df["p_activity"].notna() | df["_explicit_inactive"]]
    # pActivity outside 2-12 is physically implausible for a small molecule.
    df = df[df["_explicit_inactive"] | df["p_activity"].between(2.0, 12.0)]
    log.info("kept %d / %d rows with a usable potency value", len(df), n0)
    return df.reset_index(drop=True)


def standardize_structures(df: pd.DataFrame, *, cache_path=None) -> pd.DataFrame:
    """
    Standardize each unique SMILES once, then map the result back onto the rows.

    Standardization dominates curation cost (~180k unique structures), so it is
    deduplicated first and the mapping is cached to disk. Molecular weight is
    computed here too, since it is needed to convert mass-concentration units.
    """
    from rdkit.Chem import Descriptors

    uniq = df["canonical_smiles"].astype(str).unique()
    cache_path = cache_path or (DATA_DIR / "standardization_cache.parquet")

    cached: dict[str, tuple] = {}
    if cache_path.exists():
        prev = pd.read_parquet(cache_path)
        cached = {
            r.input_smiles: (r.std_smiles, r.inchikey, r.mol_wt)
            for r in prev.itertuples()
        }
        log.info("standardization cache: %d entries", len(cached))

    todo = [s for s in uniq if s not in cached]
    log.info("standardizing %d unique structures (%d cached)", len(todo), len(uniq) - len(todo))
    for i, smi in enumerate(todo):
        if i and i % 5000 == 0:
            log.info("  %d / %d", i, len(todo))
        res = standardize_smiles(smi)
        if res.ok:
            cached[smi] = (res.smiles, res.inchikey, float(Descriptors.MolWt(res.mol)))
        else:
            cached[smi] = (None, None, float("nan"))

    if todo:
        pd.DataFrame(
            [{"input_smiles": k, "std_smiles": v[0], "inchikey": v[1], "mol_wt": v[2]}
             for k, v in cached.items()]
        ).to_parquet(cache_path, index=False)

    src = df["canonical_smiles"].astype(str)
    df = df.copy()
    df["std_smiles"] = src.map(lambda s: cached.get(s, (None,))[0])
    df["inchikey"] = src.map(lambda s: cached.get(s, (None, None))[1])
    df["mol_wt"] = src.map(lambda s: cached.get(s, (None, None, float("nan")))[2])

    before = len(df)
    df = df[df["std_smiles"].notna()].reset_index(drop=True)
    log.info("dropped %d rows whose structure failed standardization", before - len(df))
    return df


def aggregate_per_molecule_target(df: pd.DataFrame) -> pd.DataFrame:
    """
    Collapse replicate measurements to one pActivity per molecule-target pair.

    The median is used rather than the max, so a single optimistic outlier assay
    cannot promote a compound to 'active'.

    Consistency is judged on the interquartile range, not the full min-to-max
    range. Range punishes exactly the wrong compounds: a marketed drug carries
    hundreds of measurements against its primary target across mutants, cell
    lines and assay formats, so its span is wide even when the bulk of the
    values agree closely. Filtering on range therefore deletes the
    best-characterised compounds in the dataset - gefitinib's EGFR data and
    imatinib's ABL1 data both vanish, leaving them mislabelled as weak - while
    thinly-measured compounds pass untouched. The IQR ignores those tails, so a
    well-studied drug survives while a genuinely contradictory pair is still
    dropped. Fewer than four measurements have no meaningful quartiles, so those
    fall back to the range.
    """
    df = df.copy()
    df["_censored_upper"] = df.get(
        "relation", pd.Series(["="] * len(df), index=df.index)
    ).isin([">", ">="]).astype(float)
    df.loc[df["_explicit_inactive"] & df["p_activity"].isna(), "p_activity"] = 4.0
    if "document_year" not in df.columns:
        # Downloads cached before document_year was requested have no year.
        df["document_year"] = np.nan
    df["document_year"] = pd.to_numeric(df["document_year"], errors="coerce")
    # A handful of records carry placeholder years; anything outside the range
    # in which ChEMBL literature actually exists is treated as missing.
    df.loc[~df["document_year"].between(1900, 2100), "document_year"] = np.nan

    def _spread(s: pd.Series) -> float:
        v = s.dropna().to_numpy()
        if len(v) < 2:
            return 0.0
        if len(v) < 4:
            return float(v.max() - v.min())
        q1, q3 = np.percentile(v, [25, 75])
        return float(q3 - q1)

    agg = (
        df.groupby(["std_smiles", "target_chembl_id"], as_index=False)
        .agg(
            p_activity=("p_activity", "median"),
            n_measurements=("p_activity", "size"),
            p_spread=("p_activity", _spread),
            # Earliest literature report of this molecule-target pair. Carried
            # through so evaluation can split on time (train on older work, test
            # on newer) instead of only on scaffold.
            first_year=("document_year", "min"),
            # Fraction of the evidence that is an upper bound ('>10 uM').
            # Aggregation otherwise turns ">10 uM" - which means the compound
            # is WEAKER than 10 uM - into a plain 10 uM, and any threshold at
            # or below that value then labels a non-binder as active. That
            # inverts the meaning outright for hERG and CYP, whose thresholds
            # sit right on the median of the data.
            censored_frac=("_censored_upper", "mean"),
        )
    )
    before = len(agg)
    agg = agg[(agg["n_measurements"] == 1) | (agg["p_spread"] <= 2.0)]
    log.info("dropped %d / %d molecule-target pairs with inconsistent replicates",
             before - len(agg), before)
    return agg.reset_index(drop=True)


def _labels_from_p(p: np.ndarray, active: float, inactive: float) -> np.ndarray:
    """Three-state labelling against one class's thresholds; grey zone -> NaN."""
    out = np.full(p.shape, np.nan, dtype=np.float32)
    out[p >= active] = 1.0
    out[p < inactive] = 0.0
    return out


def build_label_matrices(agg: pd.DataFrame) -> pd.DataFrame:
    """
    Produce one row per molecule with:
      target_<CHEMBLID>  binary label per target
      class_<key>        binary label per pharmacological class
      p_<CHEMBLID>       the underlying median pActivity (kept for regression /
                         for the optimizer's reward signal)

    A class is active if ANY of its targets is active; inactive only if the
    molecule was tested on at least one of its targets and none came out active.
    """
    agg = agg.copy()
    agg["class_key"] = agg["target_chembl_id"].map(TARGET_TO_CLASS)

    molecules = sorted(agg["std_smiles"].unique())
    index = {smi: i for i, smi in enumerate(molecules)}
    n = len(molecules)

    tgt_p = np.full((n, len(TARGET_KEYS)), np.nan, dtype=np.float32)
    tgt_cens = np.zeros((n, len(TARGET_KEYS)), dtype=np.float32)
    tgt_col = {t: j for j, t in enumerate(TARGET_KEYS)}

    cens_col = (agg["censored_frac"] if "censored_frac" in agg.columns
                else pd.Series(0.0, index=agg.index))
    for smi, tid, p, cf in zip(agg["std_smiles"], agg["target_chembl_id"],
                               agg["p_activity"], cens_col):
        j = tgt_col.get(tid)
        if j is None:
            continue
        i = index[smi]
        tgt_p[i, j] = p
        tgt_cens[i, j] = 0.0 if pd.isna(cf) else float(cf)

    # Each target inherits the thresholds of the class it belongs to, so a MIC
    # against S. aureus is judged on the antibacterial scale and an IC50 against
    # EGFR on the biochemical one.
    tgt_lab = np.full((n, len(TARGET_KEYS)), np.nan, dtype=np.float32)
    for j, tid in enumerate(TARGET_KEYS):
        cls = CLASS_BY_KEY[TARGET_TO_CLASS[tid]]
        lab = _labels_from_p(
            tgt_p[:, j], cls.active_threshold, cls.inactive_threshold
        )
        # An upper bound ('>10 uM') says the compound is WEAKER than the value.
        # It can therefore support 'inactive' but never 'active': a bound above
        # the active threshold tells us nothing except that the true potency is
        # somewhere below it, so those cells are masked instead of asserted.
        mostly_censored = tgt_cens[:, j] > 0.5
        lab[mostly_censored & (lab == 1.0)] = np.nan
        tgt_lab[:, j] = lab

    cls_lab = np.full((n, len(CLASS_KEYS)), np.nan, dtype=np.float32)
    cls_p = np.full((n, len(CLASS_KEYS)), np.nan, dtype=np.float32)
    for c, ckey in enumerate(CLASS_KEYS):
        cols = [tgt_col[t] for t, k in TARGET_TO_CLASS.items() if k == ckey and t in tgt_col]
        if not cols:
            continue
        sub_lab = tgt_lab[:, cols]
        sub_p = tgt_p[:, cols]
        tested = ~np.isnan(sub_p).all(axis=1)
        any_active = np.nanmax(np.nan_to_num(sub_lab, nan=0.0), axis=1) == 1.0
        with np.errstate(invalid="ignore"):
            best_p = np.where(tested, np.nanmax(np.where(np.isnan(sub_p), -np.inf, sub_p), axis=1), np.nan)
        cls_p[:, c] = np.where(np.isfinite(best_p), best_p, np.nan)
        col = np.full(n, np.nan, dtype=np.float32)
        col[tested] = 0.0
        col[any_active] = 1.0
        # Tested but every measurement fell in the ambiguous band -> mask.
        only_ambiguous = tested & np.isnan(sub_lab).all(axis=1)
        col[only_ambiguous] = np.nan
        cls_lab[:, c] = col

    # Built in one concat rather than ~100 successive column assignments, which
    # would repeatedly re-fragment the block manager on a 180k-row frame.
    blocks = {"smiles": pd.Series(molecules, dtype="object")}
    for j, t in enumerate(TARGET_KEYS):
        blocks[f"target_{t}"] = tgt_lab[:, j]
        blocks[f"p_{t}"] = tgt_p[:, j]
    for c, k in enumerate(CLASS_KEYS):
        blocks[f"class_{k}"] = cls_lab[:, c]
        blocks[f"pmax_{k}"] = cls_p[:, c]
    blocks["scaffold"] = pd.Series(
        [murcko_scaffold_safe(s) for s in molecules], dtype="object"
    )
    blocks["n_targets_tested"] = (~np.isnan(tgt_p)).sum(axis=1)
    year_by_smiles = agg.groupby("std_smiles")["first_year"].min()
    blocks["first_year"] = year_by_smiles.reindex(molecules).to_numpy(dtype=np.float32)
    return pd.DataFrame(blocks)


def murcko_scaffold_safe(smiles: str) -> str:
    from rdkit import Chem

    mol = Chem.MolFromSmiles(smiles)
    return murcko_scaffold(mol) if mol is not None else ""


def curate(raw: pd.DataFrame) -> pd.DataFrame:
    """
    Full curation pipeline.

        clean -> standardize (gives MW) -> compute pActivity -> filter
              -> aggregate replicates -> per-class three-state labelling

    Standardization comes before potency conversion because mass-concentration
    units (the majority of whole-organism MIC records) cannot be converted to
    molar without a molecular weight.
    """
    cleaned = clean_activities(raw)
    std = standardize_structures(cleaned)
    std["p_activity"] = compute_p_activity(std, std["mol_wt"])
    filtered = apply_potency_filters(std)
    agg = aggregate_per_molecule_target(filtered)
    return build_label_matrices(agg)


def label_summary(dataset: pd.DataFrame) -> pd.DataFrame:
    """Per-class counts - the table to check before trusting any metric."""
    rows = []
    for k in CLASS_KEYS:
        col = dataset[f"class_{k}"]
        pos = int((col == 1).sum())
        neg = int((col == 0).sum())
        cls = CLASS_BY_KEY[k]
        rows.append({
            "class": k,
            "format": cls.assay_format,
            "active_p": cls.active_threshold,
            "inactive_p": cls.inactive_threshold,
            "positives": pos,
            "negatives": neg,
            "labelled": pos + neg,
            "positive_rate": round(pos / (pos + neg), 4) if pos + neg else 0.0,
        })
    return pd.DataFrame(rows).sort_values("labelled", ascending=False)
