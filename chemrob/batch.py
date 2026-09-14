"""
Batch screening.

Scoring one molecule per invocation is fine for exploring and useless for a
library. This runs a whole file through the pipeline and returns a flat table,
one row per molecule, that sorts and filters in any spreadsheet.

Two things it does deliberately:

  * **Featurises once, predicts in one pass.** Calling the single-molecule path
    in a loop would re-run every head per molecule; batching the matrix through
    the heads is what makes thousands of compounds practical.
  * **Keeps failures in the output.** A molecule that will not parse gets a row
    with its error rather than vanishing, so the output row count always matches
    the input and nothing disappears silently.
"""
from __future__ import annotations

import csv
import logging
from pathlib import Path

import numpy as np
import pandas as pd
from rdkit import Chem

from .config import CLASS_BY_KEY, CLASS_KEYS
from .covalent import detect_warheads
from .featurize import featurize
from .properties import profile
from .standardize import parse_user_structure

log = logging.getLogger(__name__)


def read_structures(path: str | Path) -> list[dict]:
    """
    Read .csv, .tsv, .smi or a plain list of SMILES.

    For delimited files the structure column is found by name (smiles, smile,
    structure, canonical_smiles) and otherwise falls back to the first column;
    every other column is carried through to the output so identifiers and
    existing annotations survive the round trip.
    """
    path = Path(path)
    text_exts = {".smi", ".txt", ".ism"}
    rows: list[dict] = []

    if path.suffix.lower() in text_exts:
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split()
                rows.append({"smiles": parts[0],
                             "name": parts[1] if len(parts) > 1 else ""})
        return rows

    sep = "\t" if path.suffix.lower() in {".tsv", ".tab"} else ","
    with path.open(encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh, delimiter=sep)
        if reader.fieldnames is None:
            raise ValueError(f"{path} appears to be empty")
        lookup = {c.lower().strip(): c for c in reader.fieldnames}
        col = next(
            (lookup[c] for c in
             ("smiles", "smile", "structure", "canonical_smiles", "smiles_string")
             if c in lookup),
            reader.fieldnames[0],
        )
        for r in reader:
            rec = dict(r)
            rec["smiles"] = (r.get(col) or "").strip()
            rows.append(rec)
    return rows


def screen(
    predictor,
    structures: list[dict],
    *,
    classes: list[str] | None = None,
    include_properties: bool = True,
    include_covalent: bool = True,
    batch_size: int = 512,
) -> pd.DataFrame:
    """Score every structure and return one row each, in input order."""
    keys = [k for k in (classes or CLASS_KEYS) if k in CLASS_KEYS]
    if not keys:
        raise ValueError(f"no valid activity classes in {classes!r}")
    idx = [CLASS_KEYS.index(k) for k in keys]

    records: list[dict] = []
    feats: list[np.ndarray] = []
    live: list[int] = []          # row positions that produced a molecule
    mols: list[Chem.Mol] = []

    for i, row in enumerate(structures):
        base = {k: v for k, v in row.items() if k != "smiles"}
        base["input_smiles"] = row.get("smiles", "")
        parsed = parse_user_structure(row.get("smiles", ""))
        if not parsed.ok:
            base["status"] = f"failed: {parsed.reason}"
            records.append(base)
            continue
        base["status"] = "ok"
        base["standardized_smiles"] = parsed.smiles
        base["inchikey"] = parsed.inchikey
        records.append(base)
        feats.append(featurize(parsed.mol))
        live.append(i)
        mols.append(parsed.mol)

    log.info("parsed %d of %d structures", len(live), len(structures))
    if not live:
        return pd.DataFrame(records)

    X = np.vstack(feats)
    probs = np.zeros((len(live), len(CLASS_KEYS)), dtype=np.float32)
    pots = np.full((len(live), len(CLASS_KEYS)), np.nan, dtype=np.float32)
    for start in range(0, len(live), batch_size):
        chunk = X[start : start + batch_size]
        probs[start : start + len(chunk)] = predictor.score_features(chunk)
        if predictor.bundle.regression_class is not None:
            pots[start : start + len(chunk)] = (
                predictor.bundle.regression_class.predict_matrix(chunk)
            )
        log.info("  scored %d / %d", min(start + len(chunk), len(live)), len(live))

    ad = predictor.bundle.ad
    for n, row_i in enumerate(live):
        rec = records[row_i]
        for j, key in zip(idx, keys):
            p = float(probs[n, j])
            rec[f"p_{key}"] = round(p, 4)
            if not np.isnan(pots[n, j]):
                rec[f"pAct_{key}"] = round(float(pots[n, j]), 2)
            rec[f"call_{key}"] = "active" if p >= predictor.threshold_for(key) else "inactive"

        best = int(np.argmax(probs[n, idx]))
        rec["top_class"] = keys[best]
        rec["top_probability"] = round(float(probs[n, idx][best]), 4)
        rec["n_classes_active"] = int(sum(
            probs[n, j] >= predictor.threshold_for(k) for j, k in zip(idx, keys)
        ))

        if ad is not None:
            v = ad.assess(mols[n], n_report=1)
            rec["ad_verdict"] = v.verdict
            rec["ad_max_similarity"] = round(v.max_similarity, 3)
        if include_properties:
            pr = profile(mols[n])
            rec["mw"] = pr["lipinski"]["mw"]
            rec["clogp"] = pr["lipinski"]["logp"]
            rec["tpsa"] = pr["veber"]["tpsa"]
            rec["qed"] = pr["qed"]
            rec["sa_score"] = pr["sa_score"]
            rec["lipinski_violations"] = pr["lipinski"]["violations"]
            rec["structural_alerts"] = "; ".join(pr["structural_alerts"])
        if include_covalent:
            wh = detect_warheads(mols[n])
            rec["covalent_warhead"] = "; ".join(w["name"] for w in wh)

    df = pd.DataFrame(records)
    front = [c for c in ("input_smiles", "standardized_smiles", "status",
                         "top_class", "top_probability") if c in df.columns]
    rest = [c for c in df.columns if c not in front]
    return df[front + rest]


def rank_by(df: pd.DataFrame, class_key: str, *, in_domain_only: bool = False,
            top: int | None = None) -> pd.DataFrame:
    """Sort a screening result by one class, optionally dropping out-of-domain rows."""
    col = f"p_{class_key}"
    if col not in df.columns:
        raise ValueError(f"{col} not in the results; was {class_key!r} scored?")
    out = df[df["status"] == "ok"].copy()
    if in_domain_only and "ad_verdict" in out.columns:
        out = out[out["ad_verdict"] != "out_of_domain"]
    out = out.sort_values(col, ascending=False)
    return out.head(top) if top else out


def summarize_screen(df: pd.DataFrame, classes: list[str] | None = None) -> str:
    """A short human summary of what came back, for the end of a CLI run."""
    ok = df[df["status"] == "ok"] if "status" in df.columns else df
    failed = len(df) - len(ok)
    lines = [f"Screened {len(df)} structures: {len(ok)} scored, {failed} failed to parse."]
    if ok.empty:
        return "\n".join(lines)

    if "ad_verdict" in ok.columns:
        counts = ok["ad_verdict"].value_counts().to_dict()
        lines.append("Applicability domain: " + ", ".join(
            f"{v} {k.replace('_', ' ')}" for k, v in counts.items()))

    keys = classes or [c[2:] for c in ok.columns if c.startswith("p_")]
    hits = []
    for k in keys:
        call = f"call_{k}"
        if call in ok.columns:
            n = int((ok[call] == "active").sum())
            if n:
                hits.append((n, k))
    if hits:
        hits.sort(reverse=True)
        lines.append("Predicted active: " + ", ".join(
            f"{n} {CLASS_BY_KEY[k].label.split(' /')[0].lower()}" for n, k in hits[:6]))
    else:
        lines.append("No compound was predicted active in any scored class.")

    if "covalent_warhead" in ok.columns:
        n = int((ok["covalent_warhead"].fillna("") != "").sum())
        if n:
            lines.append(f"{n} contain covalent warheads - see the covalent_warhead column.")
    return "\n".join(lines)
