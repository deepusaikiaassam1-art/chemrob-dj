"""
BindingDB ingestion - a third source, aimed at the biochemical classes.

BindingDB re-aggregates large parts of ChEMBL and PubChem, so downloading
"BindingDB_All" and deduplicating afterwards would mean fetching ~2 GB to
discover most of it is already here. The site publishes per-origin subsets
instead, which lets the duplication be avoided by construction:

    BindingDB_ChEMBL      <- skipped, already loaded
    BindingDB_PubChem     <- skipped, that is a separate source decision
    BindingDB_Articles    <- BindingDB's own literature curation   (additive)
    BindingDB_PDSPKi      <- NIMH Psychoactive Drug Screening Program (additive)
    BindingDB_Patents     <- patent-derived, 3.8 GB uncompressed   (opt-in)

PDSPKi is the most directly useful of these: it is Ki data across monoamine
transporters, opioid, histamine, dopamine and serotonin receptors, which is
exactly the target set behind the antidepressant, analgesic, antihistaminic and
antiparkinson classes.

Targets arrive as UniProt accessions rather than ChEMBL IDs, so the mapping is
built once from ChEMBL's own target-component records and cached.
"""
from __future__ import annotations

import json
import logging
import ssl
import urllib.request
import zipfile
from pathlib import Path

import pandas as pd

from .config import DATA_DIR

log = logging.getLogger(__name__)

BASE_URL = "https://www.bindingdb.org/rwd/bind/downloads/"
CACHE_DIR = DATA_DIR / "bindingdb"
CACHE_DIR.mkdir(parents=True, exist_ok=True)
MAPPING_FILE = CACHE_DIR / "uniprot_to_chembl.json"

RELEASE = "202609"
SUBSETS: dict[str, str] = {
    "pdsp": f"BindingDB_PDSPKi_{RELEASE}_tsv.zip",
    "articles": f"BindingDB_BindingDB_Articles_{RELEASE}_tsv.zip",
    "patents": f"BindingDB_Patents_{RELEASE}_tsv.zip",
}
DEFAULT_SUBSETS = ("pdsp", "articles")   # patents is opt-in: 3.8 GB uncompressed

SMILES_COL = "Ligand SMILES"
UNIPROT_COL = "UniProt (SwissProt) Primary ID of Target Chain 1"
# Endpoint columns, in the order of preference used when a row carries several.
VALUE_COLS = (("Ki (nM)", "Ki"), ("Kd (nM)", "Kd"), ("IC50 (nM)", "IC50"),
              ("EC50 (nM)", "EC50"))
USE_COLS = [SMILES_COL, UNIPROT_COL, *[c for c, _ in VALUE_COLS]]

# Potency outside this window is a transcription artefact rather than a
# measurement: 1e-4 nM would be a femtomolar binder, 1e9 nM is molar.
MIN_NM, MAX_NM = 1e-3, 1e8


def _ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    ctx.load_default_certs()
    return ctx


def build_uniprot_mapping(*, refresh: bool = False) -> dict[str, str]:
    """UniProt accession -> ChEMBL target id, for the protein targets we model."""
    if MAPPING_FILE.exists() and not refresh:
        return json.loads(MAPPING_FILE.read_text(encoding="utf-8"))

    import requests

    from .config import ALL_TARGETS

    base = "https://www.ebi.ac.uk/chembl/api/data/target/"
    mapping: dict[str, str] = {}
    for spec in ALL_TARGETS:
        if spec.kind != "protein":
            continue
        try:
            payload = requests.get(f"{base}{spec.chembl_id}.json", timeout=60).json()
        except Exception as exc:  # noqa: BLE001
            log.warning("could not resolve %s: %s", spec.chembl_id, exc)
            continue
        for comp in payload.get("target_components", []):
            acc = comp.get("accession")
            if acc:
                mapping[acc] = spec.chembl_id
    MAPPING_FILE.write_text(json.dumps(mapping, indent=1), encoding="utf-8")
    log.info("mapped %d UniProt accessions to %d ChEMBL targets",
             len(mapping), len(set(mapping.values())))
    return mapping


def download(subset: str, *, refresh: bool = False) -> Path:
    if subset not in SUBSETS:
        raise ValueError(f"unknown subset {subset!r}; expected one of {list(SUBSETS)}")
    dest = CACHE_DIR / SUBSETS[subset]
    if dest.exists() and dest.stat().st_size > 100_000 and not refresh:
        log.info("BindingDB %s cached (%.1f MB)", subset, dest.stat().st_size / 1e6)
        return dest

    url = BASE_URL + SUBSETS[subset]
    log.info("downloading BindingDB %s ...", subset)
    req = urllib.request.Request(url, headers={"User-Agent": "ChemRobDJ/0.2"})
    with urllib.request.urlopen(req, timeout=1800, context=_ssl_context()) as r:
        payload = r.read()
    if payload[:2] != b"PK":
        raise RuntimeError(
            f"BindingDB returned {len(payload)} bytes that are not a zip archive; "
            "the download path may have changed"
        )
    dest.write_bytes(payload)
    log.info("BindingDB %s: %.1f MB -> %s", subset, len(payload) / 1e6, dest)
    return dest


def _parse_value(raw: object) -> tuple[float, str]:
    """BindingDB writes censored values as '>1000' / '<0.5' in the same column."""
    s = str(raw).strip()
    if not s or s.lower() in {"nan", "none"}:
        return float("nan"), "="
    rel = "="
    if s[0] in "><":
        rel = s[0]
        s = s[1:].lstrip("=")
    try:
        return float(s), rel
    except ValueError:
        return float("nan"), "="


def _read_subset(path: Path, chunksize: int = 200_000):
    """
    Stream the TSV in chunks.

    These files run to 3.8 GB uncompressed with 640 columns, of which six are
    needed. Reading whole would exhaust memory on a 16 GB machine, so rows are
    filtered to mapped targets chunk by chunk and only then accumulated.
    """
    with zipfile.ZipFile(path) as z:
        name = next(n for n in z.namelist() if n.lower().endswith((".tsv", ".txt")))
        with z.open(name) as fh:
            yield from pd.read_csv(
                fh, sep="\t", usecols=lambda c: c in USE_COLS,
                chunksize=chunksize, low_memory=False,
                on_bad_lines="skip", dtype=str,
            )


def to_activity_records(path: Path, mapping: dict[str, str],
                        *, source_label: str) -> pd.DataFrame:
    """Convert one BindingDB subset into the shared long-format schema."""
    frames: list[pd.DataFrame] = []
    seen = kept = 0

    for chunk in _read_subset(path):
        seen += len(chunk)
        if UNIPROT_COL not in chunk.columns or SMILES_COL not in chunk.columns:
            continue
        chunk = chunk[chunk[UNIPROT_COL].isin(mapping) & chunk[SMILES_COL].notna()]
        if chunk.empty:
            continue

        # Take the first endpoint present, preferring direct binding constants.
        value = pd.Series(float("nan"), index=chunk.index, dtype="float64")
        relation = pd.Series("=", index=chunk.index, dtype="object")
        vtype = pd.Series(pd.NA, index=chunk.index, dtype="object")
        for col, label in VALUE_COLS:
            if col not in chunk.columns:
                continue
            need = value.isna()
            if not need.any():
                break
            parsed = chunk.loc[need, col].map(_parse_value)
            value.loc[need] = [p[0] for p in parsed]
            relation.loc[need] = [p[1] for p in parsed]
            vtype.loc[need & value.notna()] = label

        chunk = chunk.assign(_v=value, _r=relation, _t=vtype)
        chunk = chunk[chunk["_v"].between(MIN_NM, MAX_NM)]
        if chunk.empty:
            continue
        kept += len(chunk)
        frames.append(pd.DataFrame({
            "molecule_chembl_id": pd.NA,
            "canonical_smiles": chunk[SMILES_COL].astype(str),
            "standard_type": chunk["_t"].astype(str),
            "standard_relation": chunk["_r"].astype(str),
            "standard_value": chunk["_v"].astype(float),
            "standard_units": "nM",
            "pchembl_value": pd.NA,
            "target_chembl_id": chunk[UNIPROT_COL].map(mapping),
            "assay_chembl_id": pd.NA,
            "assay_type": "B",
            "data_validity_comment": pd.NA,
            "activity_comment": pd.NA,
            "document_chembl_id": pd.NA,
            "document_year": pd.NA,
            "standard_flag": 1,
            "potential_duplicate": 0,
            "bao_format": pd.NA,
            "source": source_label,
        }))

    if not frames:
        log.warning("BindingDB %s: nothing matched the modelled targets", source_label)
        return pd.DataFrame()
    out = pd.concat(frames, ignore_index=True)
    log.info("BindingDB %s: %d of %d rows on modelled targets (%d compounds)",
             source_label, kept, seen, out["canonical_smiles"].nunique())
    return out


def fetch(subsets: tuple[str, ...] = DEFAULT_SUBSETS, *,
          refresh: bool = False) -> pd.DataFrame:
    """Download and normalise the requested BindingDB subsets."""
    mapping = build_uniprot_mapping()
    frames = []
    for name in subsets:
        path = download(name, refresh=refresh)
        df = to_activity_records(path, mapping, source_label=f"BindingDB:{name}")
        if not df.empty:
            frames.append(df)
    if not frames:
        return pd.DataFrame()
    return pd.concat(frames, ignore_index=True)


def summary(records: pd.DataFrame) -> pd.DataFrame:
    from .config import TARGET_BY_ID

    if records.empty:
        return pd.DataFrame()
    rows = []
    for tid, grp in records.groupby("target_chembl_id"):
        rows.append({
            "target": tid,
            "name": TARGET_BY_ID[tid].name if tid in TARGET_BY_ID else tid,
            "records": len(grp),
            "compounds": grp["canonical_smiles"].nunique(),
            "exact": int((grp["standard_relation"] == "=").sum()),
        })
    return pd.DataFrame(rows).sort_values("records", ascending=False)
