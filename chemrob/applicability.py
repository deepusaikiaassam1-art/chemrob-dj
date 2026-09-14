"""
Applicability domain.

The gap the deck flags as "false confidence": a model asked about chemistry it
has never seen will still return 0.87 with no hint that the number is invented.
This module answers "is this molecule inside the space the model learned from?"
before any probability is shown to the user.

Two signals are combined:
  * max Tanimoto similarity to the training set - is there any near neighbour?
  * mean similarity of the k nearest - is it in a populated region, or next to
    one lucky outlier?

Per-class coverage is reported too, because a molecule can be well inside the
kinase-inhibitor region and completely outside the antiprotozoal one.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

import joblib
import numpy as np
from rdkit import Chem, DataStructs

from .config import AD_BORDERLINE, AD_IN_DOMAIN, CLASS_KEYS
from .featurize import ecfp4_bitvect

log = logging.getLogger(__name__)


@dataclass
class ADVerdict:
    max_similarity: float
    mean_top_k: float
    verdict: str                      # 'in_domain' | 'borderline' | 'out_of_domain'
    nearest_neighbours: list[dict] = field(default_factory=list)
    per_class: dict[str, dict] = field(default_factory=dict)

    @property
    def reliable(self) -> bool:
        return self.verdict == "in_domain"

    def to_dict(self) -> dict:
        return {
            "max_similarity": round(self.max_similarity, 4),
            "mean_top_k": round(self.mean_top_k, 4),
            "verdict": self.verdict,
            "nearest_neighbours": self.nearest_neighbours,
            "per_class": self.per_class,
        }


class ApplicabilityDomain:
    """Fingerprint index over the training set, with per-class active subsets."""

    def __init__(self, k: int = 5) -> None:
        self.k = k
        self.smiles: list[str] = []
        self.fps: list = []
        self.class_active_rows: dict[str, np.ndarray] = {}

    def fit(self, smiles: list[str], Y_class: np.ndarray | None = None) -> "ApplicabilityDomain":
        self.smiles = list(smiles)
        self.fps = []
        keep: list[int] = []
        for i, smi in enumerate(self.smiles):
            mol = Chem.MolFromSmiles(smi)
            if mol is None:
                continue
            self.fps.append(ecfp4_bitvect(mol))
            keep.append(i)
        self.smiles = [self.smiles[i] for i in keep]

        if Y_class is not None:
            Y = Y_class[keep]
            for j, key in enumerate(CLASS_KEYS):
                self.class_active_rows[key] = np.flatnonzero(Y[:, j] == 1.0)
        log.info("applicability domain indexed on %d training molecules", len(self.fps))
        return self

    def assess(self, mol: Chem.Mol, *, n_report: int = 3) -> ADVerdict:
        if not self.fps:
            return ADVerdict(0.0, 0.0, "unknown")
        query = ecfp4_bitvect(mol)
        sims = np.asarray(DataStructs.BulkTanimotoSimilarity(query, self.fps), dtype=np.float32)

        order = np.argsort(-sims)
        top = order[: max(self.k, n_report)]
        max_sim = float(sims[order[0]])
        mean_k = float(sims[order[: self.k]].mean())

        if max_sim >= AD_IN_DOMAIN:
            verdict = "in_domain"
        elif max_sim >= AD_BORDERLINE:
            verdict = "borderline"
        else:
            verdict = "out_of_domain"

        neighbours = [
            {"smiles": self.smiles[i], "similarity": round(float(sims[i]), 4)}
            for i in top[:n_report]
        ]

        per_class: dict[str, dict] = {}
        for key, rows in self.class_active_rows.items():
            if len(rows) == 0:
                per_class[key] = {"max_similarity": 0.0, "n_actives": 0, "supported": False}
                continue
            cs = sims[rows]
            m = float(cs.max())
            per_class[key] = {
                "max_similarity": round(m, 4),
                "n_actives": int(len(rows)),
                "supported": m >= AD_BORDERLINE,
            }

        return ADVerdict(max_sim, mean_k, verdict, neighbours, per_class)

    def save(self, path) -> None:
        joblib.dump(self, path, compress=3)

    @staticmethod
    def load(path) -> "ApplicabilityDomain":
        return joblib.load(path)


def confidence_label(prob: float, ad: ADVerdict) -> str:
    """
    Collapse probability + domain coverage into the single word shown in the UI.
    A high probability outside the domain is explicitly not called 'high'.
    """
    if ad.verdict == "out_of_domain":
        return "unreliable (outside training space)"
    conf = "high" if prob >= 0.75 or prob <= 0.25 else "moderate" if prob >= 0.6 or prob <= 0.4 else "low"
    if ad.verdict == "borderline" and conf == "high":
        conf = "moderate"
    return conf
