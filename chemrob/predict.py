"""
Inference orchestration - the path a molecule takes through the whole system.

    input text -> standardize -> featurize
                              -> functional-group deep search   (Module 1)
                              -> whole-molecule activity heads  (Module 1)
                              -> core-ring activity heads       (Module 2)
                              -> per-target likelihood
                              -> applicability-domain check
                              -> attribution / explanation
                              -> disease-context re-ranking     (Module 4)

Every probability leaves this module paired with the evidence behind it: the
decision threshold it was compared against, whether the molecule is inside the
training space, and how many actives support that class nearby. A bare number
with none of that attached is what the deck calls false confidence.
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
from rdkit import Chem

from . import explain as explain_mod
from . import covalent, fg_enrichment, kg, properties, selectivity as sel_mod
from .applicability import ApplicabilityDomain, confidence_label
from .config import (ARTIFACT_DIR, CLASS_BY_KEY, CLASS_KEYS, LIABILITY_CLASS_KEYS,
                     TARGET_BY_ID, TARGET_KEYS)
from .featurize import featurize
from .fgroups import describe_groups
from .models.baseline import BaselineMultiTask
from .models.regression import BaselineMultiTaskRegressor, format_potency
from .scaffold import murcko_scaffold
from .standardize import parse_user_structure
from .train import ART

log = logging.getLogger(__name__)


@dataclass
class LoadedBundle:
    activity: BaselineMultiTask | None = None
    target: BaselineMultiTask | None = None
    scaffold: BaselineMultiTask | None = None
    ad: ApplicabilityDomain | None = None
    fg_table: pd.DataFrame | None = None
    mmp_rules: pd.DataFrame | None = None
    regression_class: BaselineMultiTaskRegressor | None = None
    regression_target: BaselineMultiTaskRegressor | None = None
    thresholds: dict[str, float] | None = None
    metadata: dict | None = None
    dmpnn: object | None = None


class ChemRobPredictor:
    """Loads a trained bundle and answers queries about a single structure."""

    def __init__(self, artifact_dir: Path = ARTIFACT_DIR, *, use_dmpnn: bool = False) -> None:
        self.artifact_dir = Path(artifact_dir)
        self.bundle = self._load(use_dmpnn=use_dmpnn)

    # -------------------------------------------------------------- loading
    def _load(self, *, use_dmpnn: bool) -> LoadedBundle:
        b = LoadedBundle()
        if ART["activity"].exists():
            b.activity = BaselineMultiTask.load(ART["activity"])
        else:
            raise FileNotFoundError(
                f"No trained activity model at {ART['activity']}. "
                "Run scripts/build_dataset.py then scripts/train.py first."
            )
        if ART["target"].exists():
            b.target = BaselineMultiTask.load(ART["target"])
        if ART["scaffold"].exists():
            b.scaffold = BaselineMultiTask.load(ART["scaffold"])
        if ART["regression_class"].exists():
            b.regression_class = BaselineMultiTaskRegressor.load(ART["regression_class"])
        if ART["regression_target"].exists():
            b.regression_target = BaselineMultiTaskRegressor.load(ART["regression_target"])
        if ART["ad"].exists():
            b.ad = ApplicabilityDomain.load(ART["ad"])
        if ART["fg"].exists():
            b.fg_table = pd.read_parquet(ART["fg"])
        if ART["mmp"].exists():
            b.mmp_rules = pd.read_parquet(ART["mmp"])
        if ART["thresholds"].exists():
            b.thresholds = json.loads(ART["thresholds"].read_text(encoding="utf-8"))
        if ART["metadata"].exists():
            b.metadata = json.loads(ART["metadata"].read_text(encoding="utf-8"))
        if use_dmpnn:
            try:
                from .train_dmpnn import load_dmpnn

                b.dmpnn = load_dmpnn()
                if b.dmpnn is None:
                    log.warning("D-MPNN requested but no checkpoint found; using baseline")
            except ImportError:
                log.warning("PyTorch unavailable; using baseline backend")
        return b

    # ------------------------------------------------------------- scoring
    def threshold_for(self, class_key: str) -> float:
        if self.bundle.thresholds:
            return float(self.bundle.thresholds.get(class_key, 0.5))
        return 0.5

    @property
    def backend(self) -> str:
        return "dmpnn" if self.bundle.dmpnn is not None else "baseline"

    def score_features(self, X: np.ndarray) -> np.ndarray:
        """
        (n, n_classes) activity probabilities from the baseline heads.

        Always the baseline, even when the D-MPNN backend is active: this is the
        function the occlusion explainer calls, and it needs a model that takes
        a feature vector. The graph network is explained through its own
        attention weights instead.
        """
        return self.bundle.activity.predict_matrix(X)

    def score_smiles_dmpnn(self, smiles: list[str]) -> tuple[np.ndarray, np.ndarray]:
        """(activity, target) probabilities from the graph network."""
        from .train_dmpnn import predict_dmpnn

        return predict_dmpnn(self.bundle.dmpnn, smiles)

    def score_smiles(self, smiles: list[str]) -> np.ndarray:
        """Activity probabilities for a list of SMILES; NaN row for unparseable."""
        feats: list[np.ndarray] = []
        valid: list[int] = []
        for i, s in enumerate(smiles):
            mol = Chem.MolFromSmiles(s)
            if mol is None:
                continue
            feats.append(featurize(mol))
            valid.append(i)
        out = np.full((len(smiles), len(CLASS_KEYS)), np.nan, dtype=np.float32)
        if not feats:
            return out
        probs = self.score_features(np.vstack(feats))
        for row, i in enumerate(valid):
            out[i] = probs[row]
        return out

    def score_smiles_potency(self, smiles: list[str]) -> np.ndarray:
        """
        (n, n_classes) predicted pActivity. All-NaN if no regression head exists,
        so callers can treat potency as optional without branching everywhere.
        """
        out = np.full((len(smiles), len(CLASS_KEYS)), np.nan, dtype=np.float32)
        if self.bundle.regression_class is None:
            return out
        feats: list[np.ndarray] = []
        valid: list[int] = []
        for i, sm in enumerate(smiles):
            mol = Chem.MolFromSmiles(sm)
            if mol is None:
                continue
            feats.append(featurize(mol))
            valid.append(i)
        if not feats:
            return out
        pot = self.bundle.regression_class.predict_matrix(np.vstack(feats))
        for row, i in enumerate(valid):
            out[i] = pot[row]
        return out

    def _class_predict_fn(self, class_index: int):
        """A single-task probability function, for the occlusion explainer."""
        def fn(X: np.ndarray) -> np.ndarray:
            return self.score_features(X)[:, class_index]

        return fn

    # -------------------------------------------------------------- report
    def predict(
        self,
        structure: str,
        *,
        disease: str | None = None,
        explain: bool = True,
        top_classes: int = 5,
        top_targets: int = 8,
        make_svg: bool = False,
    ) -> dict:
        parsed = parse_user_structure(structure)
        if not parsed.ok:
            return {"ok": False, "error": parsed.reason, "input": structure}

        mol = parsed.mol
        smiles = parsed.smiles
        X = featurize(mol).reshape(1, -1)
        warnings: list[str] = []

        # --- covalent chemistry check, before any probability is shown
        covalent_note = covalent.covalent_caveat(mol)
        if covalent_note:
            warnings.append(covalent_note["message"])

        # --- Module 1a: functional-group deep search
        groups = describe_groups(mol)
        associations = []
        if self.bundle.fg_table is not None and not self.bundle.fg_table.empty:
            associations = fg_enrichment.associations_for_groups(
                self.bundle.fg_table, [g["key"] for g in groups]
            )

        # --- Module 1b: whole-molecule activity
        dmpnn_target_probs = None
        if self.bundle.dmpnn is not None:
            act_p, tgt_p = self.score_smiles_dmpnn([smiles])
            probs, dmpnn_target_probs = act_p[0], tgt_p[0]
            warnings.append(
                "Using the D-MPNN backend, which scores lower than the default "
                "gradient-boosting baseline on the held-out scaffold split "
                "(macro AUROC 0.855 vs 0.937). It was stopped at 10 epochs while "
                "still improving; drop --dmpnn for the stronger model."
            )
        else:
            probs = self.score_features(X)[0]

        # --- applicability domain
        ad = self.bundle.ad.assess(mol) if self.bundle.ad else None
        if ad and ad.verdict == "out_of_domain":
            warnings.append(
                "Molecule is outside the model's training space "
                f"(max Tanimoto {ad.max_similarity:.2f}); predictions below are unreliable."
            )

        activity_rows = []
        for j, key in enumerate(CLASS_KEYS):
            p = probs[j]
            if np.isnan(p):
                continue
            thr = self.threshold_for(key)
            per_class_ad = (ad.per_class.get(key) if ad else None) or {}
            activity_rows.append({
                "class_key": key,
                "class_label": CLASS_BY_KEY[key].label,
                "description": CLASS_BY_KEY[key].description,
                "probability": round(float(p), 4),
                "threshold": round(thr, 3),
                "call": "active" if p >= thr else "inactive",
                "confidence": confidence_label(float(p), ad) if ad else "unknown",
                "ad_supported": bool(per_class_ad.get("supported", False)),
                "nearest_active_similarity": per_class_ad.get("max_similarity"),
            })
        # Potency regression: a probability says "active or not", a pActivity
        # says how active. Attached per class where a regression head exists.
        if self.bundle.regression_class is not None:
            pot = self.bundle.regression_class.predict_matrix(X)[0]
            for r in activity_rows:
                j = CLASS_KEYS.index(r["class_key"])
                if not np.isnan(pot[j]):
                    r["predicted_pactivity"] = round(float(pot[j]), 2)
                    r["predicted_potency"] = format_potency(float(pot[j]))

        # Split therapeutic predictions from safety liabilities. A high hERG
        # score is not an achievement, and letting it rank among the activities
        # would invert its meaning for anyone skimming the report.
        liability_rows = [r for r in activity_rows if r["class_key"] in LIABILITY_CLASS_KEYS]
        activity_rows = [r for r in activity_rows if r["class_key"] not in LIABILITY_CLASS_KEYS]
        liability_rows.sort(key=lambda r: -r["probability"])
        activity_rows.sort(key=lambda r: -r["probability"])
        for r in liability_rows:
            r["concern"] = ("likely" if r["probability"] >= 0.6
                            else "possible" if r["probability"] >= r["threshold"]
                            else "low")

        # --- Module 2: core-ring classifier
        scaffold_smiles = murcko_scaffold(mol)
        scaffold_rows: list[dict] = []
        if self.bundle.scaffold is not None and scaffold_smiles:
            score_mol = Chem.MolFromSmiles(scaffold_smiles)
            if score_mol is not None:
                Xs = featurize(score_mol).reshape(1, -1)
                sprobs = self.bundle.scaffold.predict_matrix(Xs)[0]
                for j, key in enumerate(CLASS_KEYS):
                    if np.isnan(sprobs[j]):
                        continue
                    scaffold_rows.append({
                        "class_key": key,
                        "class_label": CLASS_BY_KEY[key].label,
                        "probability": round(float(sprobs[j]), 4),
                    })
                scaffold_rows.sort(key=lambda r: -r["probability"])
        elif not scaffold_smiles:
            warnings.append("Molecule has no ring system, so the core-ring model was skipped.")

        # --- per-target likelihood
        target_rows: list[dict] = []
        tprobs = None
        if dmpnn_target_probs is not None:
            tprobs = dmpnn_target_probs
        elif self.bundle.target is not None:
            tprobs = self.bundle.target.predict_matrix(X)[0]
        # Column order comes from the *model's* own task list, not from the
        # current config. Adding a target to config.py otherwise shifts these
        # indices past the end of a bundle trained before that edit, and the
        # report either crashes or silently attributes one target's score to
        # another. A target the loaded model has never heard of is skipped
        # until the next retrain rather than being invented.
        if tprobs is not None:
            names = (self.bundle.target.task_names
                     if self.bundle.target is not None else list(TARGET_KEYS))
            for j, tid in enumerate(names):
                if j >= len(tprobs) or np.isnan(tprobs[j]) or tid not in TARGET_BY_ID:
                    continue
                spec = TARGET_BY_ID[tid]
                target_rows.append({
                    "chembl_id": tid,
                    "name": spec.name,
                    "kind": spec.kind,
                    "probability": round(float(tprobs[j]), 4),
                })
            target_rows.sort(key=lambda r: -r["probability"])

        target_potency: dict[str, float] = {}
        if self.bundle.regression_target is not None:
            tp = self.bundle.regression_target.predict_matrix(X)[0]
            for j, tid in enumerate(self.bundle.regression_target.task_names):
                if j < len(tp) and not np.isnan(tp[j]):
                    target_potency[tid] = float(tp[j])
            for r in target_rows:
                p = target_potency.get(r["chembl_id"])
                if p is not None:
                    r["predicted_pactivity"] = round(p, 2)
                    r["predicted_potency"] = format_potency(p)

        # --- Module 4: disease context
        disease_block = None
        ranked = activity_rows
        if disease:
            match = kg.resolve_disease(disease)
            if match is None:
                warnings.append(
                    f"'{disease}' did not match any indication in the knowledge graph; "
                    "predictions are shown unweighted."
                )
            else:
                class_probs = {r["class_key"]: r["probability"] for r in activity_rows}
                ranked = kg.contextual_ranking(class_probs, match)
                wanted = set(match.entry.targets)
                disease_block = {
                    "query": disease,
                    "matched": match.to_dict(),
                    "targets": kg.targets_for_disease(match),
                    "target_predictions": [
                        r for r in target_rows if r["chembl_id"] in wanted
                    ],
                }

        # --- selectivity: a difference of predicted potencies, so it needs the
        # regression heads. Meaningless without them, hence the guard.
        selectivity_block = None
        if target_potency:
            target_prob = {r["chembl_id"]: r["probability"] for r in target_rows}
            selectivity_block = {
                "pairs": sel_mod.compute_selectivity(target_potency, target_prob),
                "promiscuity": sel_mod.promiscuity_score(target_potency),
            }

        # --- explanation for the leading class
        explanation = None
        if explain and activity_rows:
            lead = activity_rows[0]["class_key"]
            if disease_block and ranked:
                lead = ranked[0]["class_key"]
            j = CLASS_KEYS.index(lead)
            if lead in self.bundle.activity.models:
                explanation = explain_mod.explain_prediction(
                    self._class_predict_fn(j), mol, make_svg=make_svg
                )
                explanation["explained_class"] = lead
                explanation["explained_class_label"] = CLASS_BY_KEY[lead].label

        return {
            "ok": True,
            "input": {
                "raw": structure,
                "standardized_smiles": smiles,
                "inchikey": parsed.inchikey,
                "scaffold": scaffold_smiles,
            },
            "properties": properties.profile(mol),
            "functional_groups": groups,
            "functional_group_associations": associations,
            "activity_predictions": activity_rows,
            "ranked_for_disease": ranked if disease_block else None,
            "scaffold_predictions": scaffold_rows,
            "target_predictions": target_rows[:top_targets],
            "liabilities": liability_rows,
            "selectivity": selectivity_block,
            "covalent": covalent_note,
            "applicability_domain": ad.to_dict() if ad else None,
            "explanation": explanation,
            "disease_context": disease_block,
            "warnings": warnings,
            "model": {
                "backend": (
                    "multi-task D-MPNN (graph neural network)"
                    if self.bundle.dmpnn is not None
                    else (self.bundle.metadata or {}).get("backend", "baseline")
                ),
                "trained_on_molecules": (self.bundle.metadata or {}).get("n_molecules"),
                "split": (self.bundle.metadata or {}).get("split"),
            },
        }
