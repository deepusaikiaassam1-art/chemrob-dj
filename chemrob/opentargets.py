"""
Open Targets ingestion - evidence-backed disease-target associations.

The disease graph in `config.py` is 25 indications I wrote by hand. It works,
but it is an assertion: nothing behind it says *how strongly* a target is tied
to a disease, and it only covers diseases I happened to think of. Open Targets
aggregates genetic, transcriptomic, clinical-trial and literature evidence into
a scored association per target-disease pair, over the whole disease ontology.

This module queries in the efficient direction. Asking "which diseases involve
this target?" is 36 requests, one per protein target; asking the reverse would
mean walking thousands of diseases to find the few that touch our targets.
Associations are then inverted into a disease -> targets table.

Two deliberate limits:

  * Only the 36 protein targets are resolvable. Whole-organism targets
    (S. aureus, C. albicans) have no human gene, so infectious-disease
    indications stay hand-curated - which is correct, since Open Targets models
    human disease biology, not pathogen susceptibility.
  * A high association score means "this gene is implicated in this disease",
    not "inhibiting it is therapeutic". The two diverge often enough that the
    generated entries are kept alongside the curated ones rather than replacing
    them, with provenance recorded on each.
"""
from __future__ import annotations

import json
import logging
import re
import time
from pathlib import Path

import pandas as pd
import requests

from .config import ALL_TARGETS, DATA_DIR, TARGET_BY_ID, TARGET_TO_CLASS

log = logging.getLogger(__name__)

API_URL = "https://api.platform.opentargets.org/api/v4/graphql"
CACHE_DIR = DATA_DIR / "opentargets"
CACHE_DIR.mkdir(parents=True, exist_ok=True)
ENSEMBL_MAP = CACHE_DIR / "uniprot_to_ensembl.json"
ASSOCIATIONS = CACHE_DIR / "target_disease_associations.parquet"
UNIPROT_MAP = DATA_DIR / "bindingdb" / "uniprot_to_chembl.json"

# Below this, associations are mostly weak literature co-mentions.
MIN_SCORE = 0.35
DISEASES_PER_TARGET = 60

_SEARCH_QUERY = """
query S($q: String!) {
  search(queryString: $q, entityNames: ["target"], page: {index: 0, size: 3}) {
    hits { id object { ... on Target { approvedSymbol } } }
  }
}
"""

_ASSOC_QUERY = """
query T($id: String!, $size: Int!) {
  target(ensemblId: $id) {
    id
    approvedSymbol
    associatedDiseases(page: {index: 0, size: $size}) {
      count
      rows {
        score
        disease { id name therapeuticAreas { id name } }
      }
    }
  }
}
"""


def _post(query: str, variables: dict, *, retries: int = 3) -> dict:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            r = requests.post(API_URL, json={"query": query, "variables": variables},
                              timeout=120)
            if r.status_code == 200:
                return r.json()
            last = RuntimeError(f"HTTP {r.status_code}: {r.text[:160]}")
        except Exception as exc:  # noqa: BLE001
            last = exc
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"Open Targets request failed: {last}")


def uniprot_to_chembl() -> dict[str, str]:
    """Reuses the accession map built for BindingDB; rebuilds it if absent."""
    if UNIPROT_MAP.exists():
        return json.loads(UNIPROT_MAP.read_text(encoding="utf-8"))
    from .bindingdb import build_uniprot_mapping

    return build_uniprot_mapping()


def resolve_ensembl(*, refresh: bool = False) -> dict[str, str]:
    """UniProt accession -> Ensembl gene id, cached."""
    if ENSEMBL_MAP.exists() and not refresh:
        return json.loads(ENSEMBL_MAP.read_text(encoding="utf-8"))

    mapping: dict[str, str] = {}
    for acc in uniprot_to_chembl():
        try:
            hits = _post(_SEARCH_QUERY, {"q": acc})["data"]["search"]["hits"]
        except Exception as exc:  # noqa: BLE001
            log.warning("could not resolve %s: %s", acc, exc)
            continue
        if hits:
            mapping[acc] = hits[0]["id"]
        time.sleep(0.1)
    ENSEMBL_MAP.write_text(json.dumps(mapping, indent=1), encoding="utf-8")
    log.info("resolved %d UniProt accessions to Ensembl genes", len(mapping))
    return mapping


def fetch_associations(*, refresh: bool = False,
                       size: int = DISEASES_PER_TARGET) -> pd.DataFrame:
    """One row per (ChEMBL target, disease) with Open Targets' association score."""
    if ASSOCIATIONS.exists() and not refresh:
        return pd.read_parquet(ASSOCIATIONS)

    acc_to_chembl = uniprot_to_chembl()
    acc_to_ensembl = resolve_ensembl(refresh=refresh)

    rows: list[dict] = []
    for acc, ensembl in acc_to_ensembl.items():
        target_id = acc_to_chembl.get(acc)
        if target_id is None:
            continue
        try:
            payload = _post(_ASSOC_QUERY, {"id": ensembl, "size": size})
        except Exception as exc:  # noqa: BLE001
            log.warning("associations failed for %s: %s", ensembl, exc)
            continue
        target = (payload.get("data") or {}).get("target")
        if not target:
            continue
        for row in target["associatedDiseases"]["rows"]:
            if row["score"] < MIN_SCORE:
                continue
            areas = [a["name"] for a in (row["disease"].get("therapeuticAreas") or [])]
            rows.append({
                "target_chembl_id": target_id,
                "target_symbol": target["approvedSymbol"],
                "activity_class": TARGET_TO_CLASS.get(target_id, ""),
                "disease_id": row["disease"]["id"],
                "disease_name": row["disease"]["name"],
                "therapeutic_areas": "; ".join(areas),
                "score": float(row["score"]),
            })
        log.info("%s (%s): %d associations kept",
                 target["approvedSymbol"], target_id,
                 sum(1 for r in rows if r["target_chembl_id"] == target_id))
        time.sleep(0.1)

    df = pd.DataFrame(rows)
    if not df.empty:
        df.to_parquet(ASSOCIATIONS, index=False)
        log.info("Open Targets: %d associations over %d diseases, %d targets",
                 len(df), df["disease_id"].nunique(), df["target_chembl_id"].nunique())
    return df


def _slug(name: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
    return s[:48] or "disease"


def build_disease_entries(df: pd.DataFrame, *, min_targets: int = 1,
                          min_score: float = MIN_SCORE) -> pd.DataFrame:
    """
    Invert associations into disease -> targets, one row per disease.

    Diseases are ranked by the strength of their best association so the search
    bar surfaces well-evidenced indications before incidental ones.
    """
    if df.empty:
        return pd.DataFrame()

    sel = df[df["score"] >= min_score]
    rows: list[dict] = []
    for (did, name), grp in sel.groupby(["disease_id", "disease_name"]):
        grp = grp.sort_values("score", ascending=False)
        if grp["target_chembl_id"].nunique() < min_targets:
            continue
        targets = list(dict.fromkeys(grp["target_chembl_id"]))
        classes = list(dict.fromkeys(c for c in grp["activity_class"] if c))
        rows.append({
            "key": _slug(name),
            "name": name,
            "disease_id": did,
            "targets": ",".join(targets),
            "primary_classes": ",".join(classes),
            "n_targets": len(targets),
            "max_score": round(float(grp["score"].max()), 4),
            "therapeutic_areas": grp["therapeutic_areas"].iloc[0],
            "source": "OpenTargets",
        })
    out = pd.DataFrame(rows).sort_values(
        ["n_targets", "max_score"], ascending=[False, False]
    )
    log.info("built %d Open Targets disease entries", len(out))
    return out.reset_index(drop=True)


def refresh_all(*, min_targets: int = 1) -> pd.DataFrame:
    """Fetch, invert and persist the generated disease table."""
    entries = build_disease_entries(fetch_associations(), min_targets=min_targets)
    if not entries.empty:
        entries.to_parquet(CACHE_DIR / "disease_entries.parquet", index=False)
    return entries


def load_entries() -> pd.DataFrame:
    """The generated table, or an empty frame if it has never been built."""
    p = CACHE_DIR / "disease_entries.parquet"
    return pd.read_parquet(p) if p.exists() else pd.DataFrame()


def coverage_report(entries: pd.DataFrame) -> pd.DataFrame:
    """How many generated indications each activity class gains."""
    if entries.empty:
        return pd.DataFrame()
    rows = []
    for spec in ALL_TARGETS:
        if spec.kind != "protein":
            continue
        n = entries["targets"].str.contains(spec.chembl_id).sum()
        rows.append({
            "target": spec.chembl_id,
            "name": spec.name,
            "class": TARGET_TO_CLASS.get(spec.chembl_id, ""),
            "diseases": int(n),
        })
    return pd.DataFrame(rows).sort_values("diseases", ascending=False)
