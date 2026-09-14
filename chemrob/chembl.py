"""
ChEMBL web-service client.

Pulls every potency measurement recorded against the targets listed in
config.ACTIVITY_CLASSES. Results are cached per target as JSONL so that an
interrupted download resumes instead of restarting, and so the exact records a
model was trained on stay on disk and can be re-audited.
"""
from __future__ import annotations

import json
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import pandas as pd
import requests

from .config import ACCEPTED_ACTIVITY_TYPES, ALL_TARGETS, DATA_DIR, TARGET_BY_ID

log = logging.getLogger(__name__)

BASE_URL = "https://www.ebi.ac.uk/chembl/api/data"
CACHE_DIR = DATA_DIR / "chembl_cache"
CACHE_DIR.mkdir(parents=True, exist_ok=True)

PAGE_SIZE = 1000
FIELDS = (
    "molecule_chembl_id,canonical_smiles,standard_type,standard_relation,"
    "standard_value,standard_units,pchembl_value,target_chembl_id,"
    "assay_chembl_id,assay_type,data_validity_comment,activity_comment,"
    # document_year drives the time-split evaluation (train on older literature,
    # test on newer) - the only split that estimates prospective performance.
    # standard_flag / potential_duplicate let curation drop records ChEMBL itself
    # flags as non-standard or as duplicates of another entry.
    "document_chembl_id,document_year,standard_flag,potential_duplicate,bao_format"
)

_local = threading.local()


def _get_session() -> requests.Session:
    """One Session per worker thread - connection pooling without sharing state."""
    s = getattr(_local, "session", None)
    if s is None:
        s = requests.Session()
        s.headers.update(
            {"Accept": "application/json", "User-Agent": "ChemRobDJ/0.2"}
        )
        _local.session = s
    return s


def _get(url: str, params: dict | None = None, *, retries: int = 4) -> dict:
    """GET with linear back-off; ChEMBL occasionally 500s on deep pagination."""
    last: Exception | None = None
    for attempt in range(retries):
        try:
            resp = _get_session().get(url, params=params, timeout=120)
            if resp.status_code == 200:
                return resp.json()
            last = RuntimeError(f"HTTP {resp.status_code}: {resp.text[:200]}")
        except Exception as exc:  # noqa: BLE001
            last = exc
        time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"ChEMBL request failed after {retries} attempts: {last}")


def fetch_target_activities(
    target_id: str, *, max_records: int = 20000, refresh: bool = False
) -> list[dict]:
    """Download all accepted-endpoint activities for one target, with caching."""
    cache = CACHE_DIR / f"{target_id}.jsonl"
    if cache.exists() and not refresh:
        with cache.open(encoding="utf-8") as fh:
            rows = [json.loads(line) for line in fh if line.strip()]
        log.info("%s: %d activities from cache", target_id, len(rows))
        return rows

    rows: list[dict] = []
    params = {
        "target_chembl_id": target_id,
        "standard_type__in": ",".join(ACCEPTED_ACTIVITY_TYPES),
        "limit": PAGE_SIZE,
        "offset": 0,
        "only": FIELDS,
    }
    url = f"{BASE_URL}/activity.json"
    while True:
        payload = _get(url, params)
        batch = payload.get("activities", [])
        rows.extend(batch)
        meta = payload.get("page_meta", {})
        total = meta.get("total_count", 0)
        log.info("%s: %d / %s", target_id, len(rows), total)
        nxt = meta.get("next")
        if not nxt or len(rows) >= min(max_records, total or max_records) or not batch:
            break
        params["offset"] = params["offset"] + PAGE_SIZE
        time.sleep(0.15)  # stay polite to the EBI service

    with cache.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r) + "\n")
    log.info("%s: cached %d activities", target_id, len(rows))
    return rows


def truncated_targets(cap: int) -> list[str]:
    """
    Targets whose cached download hit the record cap, i.e. where ChEMBL holds
    more data than we took. These are the ones worth topping up: the classes
    they belong to are consistently the weakest in evaluation.
    """
    out = []
    for spec in ALL_TARGETS:
        f = CACHE_DIR / f"{spec.chembl_id}.jsonl"
        if f.exists() and sum(1 for _ in f.open(encoding="utf-8")) >= cap:
            out.append(spec.chembl_id)
    return out


def fetch_all(
    *,
    max_records: int = 20000,
    refresh: bool = False,
    workers: int = 6,
    only: list[str] | None = None,
) -> pd.DataFrame:
    """
    Fetch every configured target and return one long-format activity table.

    A single page round-trip to EBI takes ~10 s, and several targets run to tens
    of thousands of records, so targets are downloaded concurrently. Six workers
    keeps the total well inside the service's fair-use expectations while
    turning a two-hour serial download into roughly twenty minutes.
    """
    frames: list[pd.DataFrame] = []
    results: dict[str, list[dict]] = {}

    # `only` restricts the *re-download* to named targets; everything else still
    # loads from cache, so topping up a handful of starved targets costs one
    # short download instead of refetching all 44.
    selected = set(only) if only else None
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {
            pool.submit(
                fetch_target_activities, spec.chembl_id,
                max_records=max_records,
                refresh=refresh and (selected is None or spec.chembl_id in selected),
            ): spec
            for spec in ALL_TARGETS
        }
        for fut in as_completed(futures):
            spec = futures[fut]
            try:
                results[spec.chembl_id] = fut.result()
            except Exception as exc:  # noqa: BLE001
                log.error("%s (%s) failed: %s", spec.chembl_id, spec.name, exc)
                results[spec.chembl_id] = []

    for spec in ALL_TARGETS:
        rows = results.get(spec.chembl_id, [])
        if not rows:
            log.warning("%s (%s) returned no activities", spec.chembl_id, spec.name)
            continue
        df = pd.DataFrame(rows)
        df["target_chembl_id"] = spec.chembl_id
        frames.append(df)
    if not frames:
        raise RuntimeError("No activity data retrieved from ChEMBL.")
    out = pd.concat(frames, ignore_index=True)
    out["target_name"] = out["target_chembl_id"].map(
        lambda t: TARGET_BY_ID[t].name if t in TARGET_BY_ID else t
    )
    out["target_kind"] = out["target_chembl_id"].map(
        lambda t: TARGET_BY_ID[t].kind if t in TARGET_BY_ID else "protein"
    )
    # Provenance, so a second source can be concatenated in without the two
    # becoming indistinguishable downstream.
    out["source"] = "ChEMBL"
    return out


def release_version() -> str | None:
    """
    The ChEMBL release currently served by the API.

    The pipeline downloads from a live endpoint, so without recording this a
    result can never be reproduced: a rebuild six months later silently uses
    different data. Captured at build time and written into metadata.json.
    """
    try:
        return _get(f"{BASE_URL}/status.json").get("chembl_db_version")
    except Exception as exc:  # noqa: BLE001
        log.warning("could not read the ChEMBL release version: %s", exc)
        return None


def cache_status() -> pd.DataFrame:
    """What has already been downloaded - handy before a long training run."""
    rows = []
    for spec in ALL_TARGETS:
        f: Path = CACHE_DIR / f"{spec.chembl_id}.jsonl"
        n = sum(1 for _ in f.open(encoding="utf-8")) if f.exists() else 0
        rows.append({
            "target": spec.chembl_id, "name": spec.name,
            "kind": spec.kind, "cached_records": n,
        })
    return pd.DataFrame(rows)
