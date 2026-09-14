"""
Layer 6 - Disease-target knowledge graph and the search bar behind it.

Resolves a free-text indication onto the concrete ChEMBL targets the prediction
heads already know, then uses that resolution to re-rank predictions and to
redirect the optimizer's reward. Without this the search bar in the sketch is
decoration; with it, every downstream score is conditioned on the indication the
user actually cares about.
"""
from __future__ import annotations

import difflib
import re
from dataclasses import dataclass

import numpy as np

from .config import (
    CLASS_BY_KEY,
    CLASS_KEYS,
    DISEASES,
    DISEASE_BY_KEY,
    DiseaseEntry,
    TARGET_BY_ID,
    TARGET_KEYS,
    TARGET_TO_CLASS,
)


# --------------------------------------------------------------------------
# Open Targets extension
# --------------------------------------------------------------------------
_GENERATED: tuple[DiseaseEntry, ...] | None = None


def _load_generated() -> tuple[DiseaseEntry, ...]:
    """
    Disease entries derived from Open Targets associations, if they have been
    built. Loaded lazily and cached: most queries never need them, and the
    curated entries are searched first regardless.
    """
    global _GENERATED
    if _GENERATED is not None:
        return _GENERATED
    try:
        from .opentargets import load_entries

        df = load_entries()
    except Exception:  # noqa: BLE001
        df = None
    if df is None or df.empty:
        _GENERATED = ()
        return _GENERATED

    curated_keys = {d.key for d in DISEASES}
    entries: list[DiseaseEntry] = []
    for _, r in df.iterrows():
        key = str(r["key"])
        if key in curated_keys:
            # A hand-curated entry for the same indication wins: it is checked,
            # and it carries the infectious-disease targets Open Targets has no
            # human gene for.
            continue
        targets = tuple(t for t in str(r["targets"]).split(",") if t)
        classes = tuple(c for c in str(r["primary_classes"]).split(",") if c)
        if not targets:
            continue
        entries.append(DiseaseEntry(key, str(r["name"]), (), targets, classes))
    _GENERATED = tuple(entries)
    return _GENERATED


def all_disease_entries() -> tuple[DiseaseEntry, ...]:
    """Curated entries first, then anything Open Targets contributed."""
    return DISEASES + _load_generated()


def _normalize(text: str) -> str:
    return re.sub(r"[^a-z0-9 ]+", " ", text.lower()).strip()


@dataclass
class DiseaseMatch:
    entry: DiseaseEntry
    score: float
    matched_on: str

    def to_dict(self) -> dict:
        return {
            "key": self.entry.key,
            "name": self.entry.name,
            "score": round(self.score, 3),
            "matched_on": self.matched_on,
            "targets": [
                {"chembl_id": t, "name": TARGET_BY_ID[t].name if t in TARGET_BY_ID else t}
                for t in self.entry.targets
            ],
            "primary_classes": list(self.entry.primary_classes),
        }


def search_disease(query: str, *, top_n: int = 5) -> list[DiseaseMatch]:
    """
    Rank indications against a typed query. Exact and substring hits on the name
    or a synonym come first; fuzzy matching catches spelling variants such as
    'leishmaniasis' vs 'leishmaniosis'.
    """
    q = _normalize(query)
    if not q:
        return []

    q_tokens = set(q.split())
    results: list[DiseaseMatch] = []
    curated_keys = {d.key for d in DISEASES}
    for entry in all_disease_entries():
        candidates = [entry.name, entry.key.replace("_", " "), *entry.synonyms]
        best, how = 0.0, ""
        for cand in candidates:
            c = _normalize(cand)
            if not c:
                continue
            if q == c:
                score, label = 1.0, "exact"
            elif len(c) < 4 or len(q) < 4:
                # Short synonyms such as 'tb' or 'ra' must match a whole word,
                # otherwise 'ra' hits 'visceral' and every acronym matches
                # half the graph.
                if c in q_tokens or q in set(c.split()):
                    score, label = 0.95, "acronym"
                else:
                    continue
            elif q in c or c in q:
                score, label = 0.85, "substring"
            else:
                score = difflib.SequenceMatcher(None, q, c).ratio()
                label = "fuzzy"
                if score < 0.6:
                    continue
            if score > best:
                best, how = score, f"{label}:{cand}"
        if best > 0:
            # Nudge curated entries above generated ones at equal text score.
            if entry.key in curated_keys:
                best += 1e-6
            results.append(DiseaseMatch(entry, best, how))

    return sorted(results, key=lambda m: -m.score)[:top_n]


def resolve_disease(query: str) -> DiseaseMatch | None:
    """Best single match, or None if nothing in the graph is close enough."""
    if not query:
        return None
    if query in DISEASE_BY_KEY:
        return DiseaseMatch(DISEASE_BY_KEY[query], 1.0, "key")
    for entry in _load_generated():
        if entry.key == query:
            return DiseaseMatch(entry, 1.0, "key")
    hits = search_disease(query, top_n=1)
    return hits[0] if hits else None


def class_weights_for_disease(match: DiseaseMatch | None, *, boost: float = 1.0) -> np.ndarray:
    """
    Relevance weight per activity class given the chosen indication.

    Classes the disease maps to get weight 1; everything else is damped rather
    than zeroed, because an off-indication activity is still worth reporting as
    a possible off-target effect - it just should not lead the ranking.
    """
    w = np.full(len(CLASS_KEYS), 0.25, dtype=np.float32)
    if match is None:
        return np.ones(len(CLASS_KEYS), dtype=np.float32)
    for i, key in enumerate(CLASS_KEYS):
        if key in match.entry.primary_classes:
            w[i] = 1.0
    return w * boost


def target_weights_for_disease(match: DiseaseMatch | None) -> np.ndarray:
    """Same idea at the individual-target level, used by the target head."""
    w = np.full(len(TARGET_KEYS), 0.2, dtype=np.float32)
    if match is None:
        return np.ones(len(TARGET_KEYS), dtype=np.float32)
    wanted = set(match.entry.targets)
    for i, t in enumerate(TARGET_KEYS):
        if t in wanted:
            w[i] = 1.0
    return w


def contextual_ranking(
    class_probs: dict[str, float], match: DiseaseMatch | None
) -> list[dict]:
    """
    Re-rank activity classes for the chosen indication.

    The raw probability is always kept alongside the contextual score so the
    disease filter can never silently hide a strong off-indication prediction.
    """
    weights = class_weights_for_disease(match)
    rows = []
    for i, key in enumerate(CLASS_KEYS):
        p = class_probs.get(key)
        if p is None or (isinstance(p, float) and np.isnan(p)):
            continue
        rows.append({
            "class_key": key,
            "class_label": CLASS_BY_KEY[key].label,
            "probability": float(p),
            "disease_relevance": float(weights[i]),
            "contextual_score": float(p) * float(weights[i]),
            "on_indication": bool(match and key in match.entry.primary_classes),
        })

    if match is None:
        return sorted(rows, key=lambda r: -r["probability"])

    # On-indication classes lead, ranked among themselves by probability;
    # off-indication classes follow.
    #
    # Sorting purely on contextual_score does not achieve this. With
    # off-indication classes damped to 0.25, any of them with more than four
    # times the probability of the best on-indication class still comes out on
    # top - which is how a query for "visceral leishmaniasis" ended up leading
    # with antifungal. The damping expresses "this is secondary", not "this
    # outranks the thing you asked about", so the grouping has to be explicit.
    return sorted(rows, key=lambda r: (not r["on_indication"], -r["probability"]))


def targets_for_disease(match: DiseaseMatch | None) -> list[dict]:
    if match is None:
        return []
    return [
        {
            "chembl_id": t,
            "name": TARGET_BY_ID[t].name if t in TARGET_BY_ID else t,
            "kind": TARGET_BY_ID[t].kind if t in TARGET_BY_ID else "protein",
            "activity_class": TARGET_TO_CLASS.get(t, ""),
        }
        for t in match.entry.targets
    ]


def list_diseases(include_generated: bool = True) -> list[dict]:
    return [
        {"key": d.key, "name": d.name, "classes": list(d.primary_classes),
         "n_targets": len(d.targets),
         "source": "curated" if d.key in {c.key for c in DISEASES} else "OpenTargets"}
        for d in (all_disease_entries() if include_generated else DISEASES)
    ]
