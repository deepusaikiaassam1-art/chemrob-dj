"""
Stage 1 of the pipeline: download ChEMBL bioactivity data and curate it into the
molecule-level training table.

    python scripts/build_dataset.py --max-records 20000
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pandas as pd

from chemrob import bindingdb, chembl, coadd, curate
from chemrob.config import CURATED_DATASET, RAW_ACTIVITIES
from chemrob.keepawake import keep_awake


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-records", type=int, default=20000,
                    help="cap on activity records downloaded per target")
    ap.add_argument("--refresh", action="store_true",
                    help="ignore the on-disk cache and re-download")
    ap.add_argument("--only", nargs="*", default=None, metavar="CHEMBL_ID",
                    help="re-download only these targets (others load from cache)")
    ap.add_argument("--refresh-truncated", type=int, default=None, metavar="OLD_CAP",
                    help="re-download only the targets whose cache hit OLD_CAP, "
                         "i.e. the ones ChEMBL has more data for")
    ap.add_argument("--bindingdb", action="store_true",
                    help="include BindingDB (measured -0.0027 macro AUPRC when "
                         "ingested without assay-condition filtering; off by default)")
    ap.add_argument("--bindingdb-patents", action="store_true",
                    help="also ingest the BindingDB patent subset (3.8 GB uncompressed)")
    ap.add_argument("--no-coadd", action="store_true",
                    help="skip CO-ADD; build from ChEMBL alone")
    ap.add_argument("--refresh-coadd", action="store_true",
                    help="re-download the CO-ADD archive")
    ap.add_argument("--workers", type=int, default=6,
                    help="concurrent target downloads")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )
    log = logging.getLogger("build")

    only = args.only
    refresh = args.refresh
    if args.refresh_truncated is not None:
        only = chembl.truncated_targets(args.refresh_truncated)
        refresh = True
        if not only:
            log.info("no targets were truncated at %d - nothing to top up",
                     args.refresh_truncated)
        else:
            log.info("topping up %d truncated target(s): %s", len(only), ", ".join(only))

    release = chembl.release_version()
    log.info("ChEMBL release: %s", release or "unknown")

    log.info("Downloading ChEMBL activities ...")
    raw = chembl.fetch_all(max_records=args.max_records, refresh=refresh,
                           workers=args.workers, only=only)
    log.info("ChEMBL: %d activity records", len(raw))

    if not args.no_coadd:
        try:
            log.info("Adding CO-ADD dose-response data ...")
            extra = coadd.fetch(refresh=args.refresh_coadd)
            log.info("CO-ADD per target:\n%s",
                     coadd.summary(extra).to_string(index=False))
            raw = pd.concat([raw, extra], ignore_index=True)
            # ChEMBL's REST API returns numbers as strings while CO-ADD yields
            # floats, so the concatenated columns end up object-dtype and
            # parquet refuses to write them. Coerce the numeric fields once,
            # here, rather than leaving every consumer to guess the type.
            for col in ("standard_value", "pchembl_value", "document_year",
                        "standard_flag", "potential_duplicate"):
                if col in raw.columns:
                    raw[col] = pd.to_numeric(raw[col], errors="coerce")
            for col in ("standard_relation", "standard_units", "standard_type",
                        "activity_comment", "data_validity_comment", "source"):
                if col in raw.columns:
                    raw[col] = raw[col].astype("string")
        except Exception as exc:  # noqa: BLE001
            # A second source failing should degrade the dataset, not destroy
            # the run - the ChEMBL half is already downloaded and usable.
            log.error("CO-ADD ingestion failed (%s); continuing with ChEMBL only", exc)

    if args.bindingdb:
        try:
            log.info("Adding BindingDB ...")
            subsets = bindingdb.DEFAULT_SUBSETS + (
                ("patents",) if args.bindingdb_patents else ()
            )
            extra = bindingdb.fetch(subsets)
            if not extra.empty:
                log.info("BindingDB per target:\n%s",
                         bindingdb.summary(extra).head(12).to_string(index=False))
                raw = pd.concat([raw, extra], ignore_index=True)
                for col in ("standard_value", "pchembl_value", "document_year",
                            "standard_flag", "potential_duplicate"):
                    if col in raw.columns:
                        raw[col] = pd.to_numeric(raw[col], errors="coerce")
                for col in ("standard_relation", "standard_units", "standard_type",
                            "activity_comment", "data_validity_comment", "source"):
                    if col in raw.columns:
                        raw[col] = raw[col].astype("string")
        except Exception as exc:  # noqa: BLE001
            log.error("BindingDB ingestion failed (%s); continuing without it", exc)

    raw.to_parquet(RAW_ACTIVITIES, index=False)
    log.info("raw activities: %d rows (%s) -> %s", len(raw),
             ", ".join(f"{k} {v}" for k, v in
                       raw.get("source", pd.Series(dtype=str)).value_counts().items()),
             RAW_ACTIVITIES)

    log.info("Curating ...")
    dataset = curate.curate(raw)
    dataset.to_parquet(CURATED_DATASET, index=False)
    log.info("curated dataset: %d molecules -> %s", len(dataset), CURATED_DATASET)

    # Recorded beside the data so train.py can copy it into metadata.json and
    # every reported number carries the release it came from.
    #
    # "downloaded" used to be today's date, which is the date the dataset was
    # *built*, not the date ChEMBL was queried - and those differ whenever the
    # per-target cache is reused, which is most of the time. A data availability
    # statement citing the wrong fetch date is not reproducible, so the real
    # range is taken from the cache files themselves and the build date is kept
    # separately. Package versions go in for the same reason: they were being
    # transcribed into the manuscript by hand from a live interpreter.
    import datetime as _dt
    import json as _json
    import platform as _platform

    def _cache_fetch_range() -> dict:
        files = sorted(chembl.CACHE_DIR.glob("*.jsonl"))
        if not files:
            return {}
        stamps = sorted(_dt.date.fromtimestamp(f.stat().st_mtime) for f in files)
        return {"chembl_fetched_from": stamps[0].isoformat(),
                "chembl_fetched_to": stamps[-1].isoformat(),
                "chembl_cached_targets": len(files)}

    def _versions() -> dict:
        out = {"python": _platform.python_version()}
        for mod in ("rdkit", "sklearn", "numpy", "pandas", "scipy", "joblib"):
            try:
                out[mod] = __import__(mod).__version__
            except Exception:
                out[mod] = None
        return out

    (CURATED_DATASET.parent / "provenance.json").write_text(_json.dumps({
        "chembl_release": release,
        **_cache_fetch_range(),
        "coadd_release": (None if args.no_coadd else coadd.DOSE_RESPONSE_FILE),
        "bindingdb_included": bool(args.bindingdb),
        "built": _dt.date.today().isoformat(),
        "max_records_per_target": args.max_records,
        "sources": sorted(raw["source"].dropna().unique().tolist())
        if "source" in raw.columns else ["ChEMBL"],
        "n_raw_records": int(len(raw)),
        "n_curated_molecules": int(len(dataset)),
        "versions": _versions(),
    }, indent=2), encoding="utf-8")
    log.info("provenance written to %s", CURATED_DATASET.parent / "provenance.json")

    summary = curate.label_summary(dataset)
    log.info("Label summary:\n%s", summary.to_string(index=False))
    return 0


if __name__ == "__main__":
    # Windows counts a long fit with no keyboard input as idle and will
    # suspend underneath it; that is what killed a seed run mid-way.
    with keep_awake("the dataset build"):
        raise SystemExit(main())
