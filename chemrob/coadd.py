"""
CO-ADD ingestion - a second data source alongside ChEMBL.

CO-ADD (the Community for Open Antimicrobial Drug Discovery, University of
Queensland) screens open compound collections against the ESKAPE panel plus
*C. albicans* and *C. neoformans* - precisely the classes that evaluate worst
here. Its dose-response release carries real MIC values, so it merges into the
same target columns as the ChEMBL organism data rather than needing a parallel
representation.

Three things make it different from ChEMBL, and each is handled explicitly:

**Units are mass concentration.** 80% of the values are ug/mL, which cannot be
turned into pActivity without a molecular weight. ChEMBL's curation path has no
reason to handle that, so the conversion is done here, per compound, from the
structure itself.

**85% of values are censored `>` bounds.** That is what a screening library
looks like: most compounds do nothing. Those rows are not waste - they are the
true negatives the model is otherwise short of, and the existing curation
already treats a weak `>` bound as evidence of inactivity.

**Compounds are mostly not in ChEMBL.** The libraries screened are diversity
sets rather than medicinal-chemistry series, so they extend the applicability
domain into chemistry the ChEMBL-trained model has never seen.

Certificate note: db.co-add.org serves an incomplete chain that certifi
rejects, so downloads go through urllib against the system trust store instead
of `requests`.
"""
from __future__ import annotations

import io
import logging
import ssl
import urllib.request
import zipfile
from pathlib import Path

import pandas as pd
from rdkit import Chem, RDLogger
from rdkit.Chem import Descriptors

from .config import DATA_DIR

RDLogger.DisableLog("rdApp.*")
log = logging.getLogger(__name__)

BASE_URL = "https://db.co-add.org/javax.faces.resource/"
DOSE_RESPONSE_FILE = "CO-ADD_DoseResponseData_r03_01-02-2020_CSV.zip"
CACHE_DIR = DATA_DIR / "coadd"
CACHE_DIR.mkdir(parents=True, exist_ok=True)

# CO-ADD organism -> the ChEMBL target the measurement is merged into, so both
# sources contribute to one head rather than splitting the evidence.
ORGANISM_TO_TARGET: dict[str, str] = {
    "Staphylococcus aureus": "CHEMBL352",
    "Escherichia coli": "CHEMBL354",
    "Pseudomonas aeruginosa": "CHEMBL348",
    "Klebsiella pneumoniae": "CHEMBL350",
    "Acinetobacter baumannii": "CHEMBL614425",
    "Enterococcus faecium": "CHEMBL357",
    "Candida albicans": "CHEMBL366",
    "Cryptococcus neoformans": "CHEMBL365",
}

# Value types that represent growth inhibition. CC50/HC10 are mammalian
# cytotoxicity and haemolysis - valuable for selectivity, but not the same
# question as antimicrobial potency, so they are excluded here.
ACCEPTED_VALUE_TYPES = {"MIC", "MIC50", "IC50", "EC50"}


def _ssl_context() -> ssl.SSLContext:
    """System trust store: certifi lacks the issuer CO-ADD's chain relies on."""
    ctx = ssl.create_default_context()
    ctx.load_default_certs()
    return ctx


def download(*, refresh: bool = False) -> Path:
    """Fetch (and cache) the CO-ADD dose-response release."""
    dest = CACHE_DIR / DOSE_RESPONSE_FILE
    if dest.exists() and not refresh:
        log.info("CO-ADD archive already cached (%.1f MB)", dest.stat().st_size / 1e6)
        return dest

    url = f"{BASE_URL}{DOSE_RESPONSE_FILE}.xhtml?ln=files"
    log.info("downloading CO-ADD dose-response data ...")
    req = urllib.request.Request(url, headers={"User-Agent": "ChemRobDJ/0.2"})
    with urllib.request.urlopen(req, timeout=600, context=_ssl_context()) as r:
        payload = r.read()
    dest.write_bytes(payload)
    log.info("CO-ADD: %.1f MB -> %s", len(payload) / 1e6, dest)
    return dest


def _read_archive(path: Path) -> pd.DataFrame:
    with zipfile.ZipFile(path) as z:
        name = next(n for n in z.namelist() if n.lower().endswith(".csv"))
        return pd.read_csv(io.BytesIO(z.read(name)), low_memory=False)


def _parse_value(raw: object) -> tuple[float, str]:
    """Split CO-ADD's '>10' / '5' notation into (number, relation)."""
    s = str(raw).strip()
    if not s or s.lower() == "nan":
        return float("nan"), "="
    if s.startswith(">="):
        return _to_float(s[2:]), ">"
    if s.startswith("<="):
        return _to_float(s[2:]), "<"
    if s.startswith(">"):
        return _to_float(s[1:]), ">"
    if s.startswith("<"):
        return _to_float(s[1:]), "<"
    return _to_float(s), "="


def _to_float(s: str) -> float:
    try:
        return float(s)
    except (TypeError, ValueError):
        return float("nan")


def _molecular_weights(smiles: list[str]) -> dict[str, float]:
    """MW per unique structure - needed to turn ug/mL into a molar quantity."""
    out: dict[str, float] = {}
    for smi in smiles:
        mol = Chem.MolFromSmiles(smi) if isinstance(smi, str) else None
        out[smi] = Descriptors.MolWt(mol) if mol is not None else float("nan")
    return out


def to_activity_records(df: pd.DataFrame) -> pd.DataFrame:
    """
    Convert the CO-ADD table into the same long format as ChEMBL activities, so
    downstream curation needs no knowledge of where a row came from.
    """
    df = df[df["ORGANISM"].isin(ORGANISM_TO_TARGET)].copy()
    df = df[df["DRVAL_TYPE"].isin(ACCEPTED_VALUE_TYPES)]
    df = df[df["SMILES"].notna()]
    log.info("CO-ADD: %d rows against mapped organisms", len(df))

    parsed = df["DRVAL_MEDIAN"].map(_parse_value)
    df["value"] = [p[0] for p in parsed]
    df["relation"] = [p[1] for p in parsed]
    df = df[df["value"].notna() & (df["value"] > 0)]

    mw = _molecular_weights(df["SMILES"].unique().tolist())
    df["_mw"] = df["SMILES"].map(mw)

    unit = df["DRVAL_UNIT"].astype(str).str.strip().str.lower()
    # ug/mL -> uM needs the molecular weight: uM = 1000 * (ug/mL) / MW.
    is_mass = unit.isin({"ug/ml", "µg/ml", "mcg/ml"})
    is_molar = unit.isin({"um", "µm"})

    micromolar = pd.Series(float("nan"), index=df.index, dtype="float64")
    micromolar[is_molar] = df.loc[is_molar, "value"]
    micromolar[is_mass] = 1000.0 * df.loc[is_mass, "value"] / df.loc[is_mass, "_mw"]

    df["standard_value"] = micromolar * 1000.0     # nM
    df["standard_units"] = "nM"
    before = len(df)
    df = df[df["standard_value"].notna() & (df["standard_value"] > 0)]
    if before != len(df):
        log.info("CO-ADD: dropped %d rows with unusable units or weights",
                 before - len(df))

    out = pd.DataFrame({
        "molecule_chembl_id": df["COADD_ID"].astype(str),
        "canonical_smiles": df["SMILES"].astype(str),
        "standard_type": "MIC",
        "standard_relation": df["relation"],
        "standard_value": df["standard_value"],
        "standard_units": "nM",
        "pchembl_value": pd.NA,
        "target_chembl_id": df["ORGANISM"].map(ORGANISM_TO_TARGET),
        "assay_chembl_id": df["ASSAY_ID"].astype(str),
        "assay_type": "F",                       # functional / whole-organism
        "data_validity_comment": pd.NA,
        "activity_comment": pd.NA,
        "document_chembl_id": df["PROJECT_ID"].astype(str),
        # The release is dated February 2020; no per-record year is published.
        "document_year": 2020,
        "standard_flag": 1,
        "potential_duplicate": 0,
        "bao_format": "BAO_0000218",             # organism-based format
        "source": "CO-ADD",
    })
    log.info("CO-ADD: %d usable activity records over %d compounds",
             len(out), out["canonical_smiles"].nunique())
    return out.reset_index(drop=True)


def fetch(*, refresh: bool = False) -> pd.DataFrame:
    """Download, parse and normalise CO-ADD in one call."""
    return to_activity_records(_read_archive(download(refresh=refresh)))


def summary(records: pd.DataFrame) -> pd.DataFrame:
    """Per-target counts, for the build log."""
    from .config import TARGET_BY_ID

    rows = []
    for tid, grp in records.groupby("target_chembl_id"):
        rel = grp["standard_relation"]
        rows.append({
            "target": tid,
            "name": TARGET_BY_ID[tid].name if tid in TARGET_BY_ID else tid,
            "records": len(grp),
            "exact": int((rel == "=").sum()),
            "censored": int((rel == ">").sum()),
            "compounds": grp["canonical_smiles"].nunique(),
        })
    return pd.DataFrame(rows).sort_values("records", ascending=False)
