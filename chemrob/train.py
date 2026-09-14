"""
Training pipeline.

Produces every artifact the application needs, all fitted on the training split
only:

    baseline_activity.joblib   whole-molecule activity heads      (Module 1)
    baseline_target.joblib     per-target likelihood heads
    baseline_scaffold.joblib   core-ring activity heads           (Module 2)
    applicability.joblib       fingerprint index for the AD check
    fg_enrichment.parquet      functional-group -> activity table (Module 1)
    mmp_rules.parquet          matched-pair transformation rules  (Module 3)
    thresholds.json            per-task decision thresholds
    metrics.json               held-out scaffold-split performance
    metadata.json              provenance for the whole bundle

Nothing here touches the test split except the final metric computation.
"""
from __future__ import annotations

import gc
import json
import logging
import time
from dataclasses import asdict, dataclass

import numpy as np
import pandas as pd
from rdkit import Chem

from . import fg_enrichment, mmp
from .applicability import ApplicabilityDomain
from .config import (
    ARTIFACT_DIR,
    CURATED_DATASET,
    CLASS_BY_KEY,
    CLASS_KEYS,
    RANDOM_SEED,
    TARGET_KEYS,
)
from .dataset import Dataset, scaffold_level_labels
from .evaluate import (
    best_thresholds,
    evaluate_multitask,
    evaluate_regression,
    summarize,
    summarize_by_block,
    summarize_regression,
)
from .featurize import featurize
from .models.baseline import BaselineMultiTask
from .models.regression import BaselineMultiTaskRegressor

log = logging.getLogger(__name__)

ART = {
    "activity": ARTIFACT_DIR / "baseline_activity.joblib",
    "target": ARTIFACT_DIR / "baseline_target.joblib",
    "scaffold": ARTIFACT_DIR / "baseline_scaffold.joblib",
    "regression_class": ARTIFACT_DIR / "regression_class.joblib",
    "regression_target": ARTIFACT_DIR / "regression_target.joblib",
    "ad": ARTIFACT_DIR / "applicability.joblib",
    "fg": ARTIFACT_DIR / "fg_enrichment.parquet",
    "mmp": ARTIFACT_DIR / "mmp_rules.parquet",
    "thresholds": ARTIFACT_DIR / "thresholds.json",
    "metrics": ARTIFACT_DIR / "metrics.json",
    "metadata": ARTIFACT_DIR / "metadata.json",
    "dmpnn": ARTIFACT_DIR / "dmpnn.pt",
}


@dataclass
class TrainConfig:
    train_mmp: bool = True
    train_scaffold: bool = True
    train_targets: bool = True
    train_regression: bool = True
    mmp_min_pairs: int = 3
    scaffold_min_members: int = 3
    seed: int = RANDOM_SEED


def _featurize_smiles(smiles: list[str]) -> np.ndarray:
    from .featurize import FEATURE_DIM

    X = np.zeros((len(smiles), FEATURE_DIM), dtype=np.float32)
    for i, s in enumerate(smiles):
        mol = Chem.MolFromSmiles(s)
        if mol is not None:
            X[i] = featurize(mol)
    return X


def train_baseline_heads(ds: Dataset, cfg: TrainConfig) -> dict:
    """
    Train the activity head and, optionally, the per-target head.

    The three splits are materialised from the memory-mapped feature file once
    and then reused across all 50 task models. Indexing the memmap per task
    instead looks like it saves memory, but each of those gathers is a scattered
    read over a 1.4 GB file; on a machine that cannot hold the file in page
    cache it turns a 25-second fit into a ten-minute one, thrashing the whole
    time. One sequential read up front costs ~1 GB and is the difference between
    minutes and hours.
    """
    tr, va, te = ds.train_idx, ds.valid_idx, ds.test_idx
    results: dict = {}

    log.info("materialising feature splits (train=%d, valid=%d, test=%d)",
             len(tr), len(va), len(te))
    X_train = np.ascontiguousarray(ds.X[tr])
    X_valid = np.ascontiguousarray(ds.X[va])
    X_test = np.ascontiguousarray(ds.X[te])
    log.info("feature splits resident: %.2f GB",
             (X_train.nbytes + X_valid.nbytes + X_test.nbytes) / 1e9)

    log.info("=== activity head: %d classes ===", len(CLASS_KEYS))
    act = BaselineMultiTask().fit(
        X_train, ds.Y_class[tr], list(CLASS_KEYS),
        X_valid=X_valid, Y_valid=ds.Y_class[va],
    )
    act.save(ART["activity"])

    prob_va = act.predict_matrix(X_valid)
    prob_te = act.predict_matrix(X_test)
    thresholds = best_thresholds(ds.Y_class[va], prob_va, list(CLASS_KEYS))
    metrics_act = evaluate_multitask(ds.Y_class[te], prob_te, list(CLASS_KEYS))
    # The pooled summary averages therapeutic and liability heads together, and
    # the liability block sits at base rates of 0.72-0.87 - its AUPRC is high for
    # arithmetic reasons. Every downstream consumer (the benchmark report, the
    # manuscript) needs the blocks separated, so write them here rather than
    # leaving each script to recompute it.
    results["activity"] = {
        "per_task": metrics_act.to_dict(orient="records"),
        "summary": summarize(metrics_act),
        "summary_by_block": summarize_by_block(metrics_act),
        "thresholds": thresholds,
    }
    log.info("activity head (test): %s", results["activity"]["summary"])

    if cfg.train_targets:
        log.info("=== target head: %d targets ===", len(TARGET_KEYS))
        tgt = BaselineMultiTask().fit(
            X_train, ds.Y_target[tr], list(TARGET_KEYS),
            X_valid=X_valid, Y_valid=ds.Y_target[va],
        )
        tgt.save(ART["target"])
        prob_te_t = tgt.predict_matrix(X_test)
        metrics_tgt = evaluate_multitask(ds.Y_target[te], prob_te_t, list(TARGET_KEYS))
        results["target"] = {
            "per_task": metrics_tgt.to_dict(orient="records"),
            "summary": summarize(metrics_tgt),
        }
        log.info("target head (test): %s", results["target"]["summary"])

    del X_train, X_valid, X_test
    gc.collect()
    return results


def train_scaffold_head(ds: Dataset, cfg: TrainConfig) -> dict:
    """
    Module 2: the core-ring classifier.

    Built from training molecules only, then evaluated on scaffolds that come
    from held-out molecules. Because the split is by scaffold, no core seen in
    training reappears at test time - which is exactly the question the module
    is meant to answer ("what does this ring system tend to do?").
    """
    log.info("=== scaffold head ===")
    tr_frame = ds.frame.iloc[ds.train_idx].reset_index(drop=True)
    # scaffold_level_labels only reads .frame and .Y_class, so the (large)
    # feature matrix is deliberately not copied into this view.
    tr_ds = Dataset(
        frame=tr_frame, smiles=[ds.smiles[i] for i in ds.train_idx],
        scaffolds=[ds.scaffolds[i] for i in ds.train_idx],
        X=np.zeros((0, 0), dtype=np.float32), Y_class=ds.Y_class[ds.train_idx],
        Y_target=ds.Y_target[ds.train_idx],
        train_idx=np.arange(len(ds.train_idx)), valid_idx=np.array([], dtype=int),
        test_idx=np.array([], dtype=int),
    )
    table = scaffold_level_labels(tr_ds, min_members=cfg.scaffold_min_members)
    if table.empty:
        log.warning("no scaffolds with enough members - skipping scaffold head")
        return {}

    scaf_smiles = table["scaffold"].astype(str).tolist()
    Xs = _featurize_smiles(scaf_smiles)
    Ys = table[[f"class_{k}" for k in CLASS_KEYS]].to_numpy(dtype=np.float32)

    # Internal split of the scaffold table for calibration.
    rng = np.random.default_rng(cfg.seed)
    perm = rng.permutation(len(table))
    cut = int(0.85 * len(table))
    s_tr, s_va = perm[:cut], perm[cut:]

    model = BaselineMultiTask().fit(
        Xs[s_tr], Ys[s_tr], list(CLASS_KEYS),
        X_valid=Xs[s_va], Y_valid=Ys[s_va],
    )
    model.save(ART["scaffold"])

    # Held-out evaluation: scaffolds of test molecules, labelled by those
    # molecules' own class labels.
    te_scaf = pd.DataFrame({
        "scaffold": [ds.scaffolds[i] for i in ds.test_idx],
        **{f"class_{k}": ds.Y_class[ds.test_idx, j] for j, k in enumerate(CLASS_KEYS)},
    })
    te_scaf = te_scaf[te_scaf["scaffold"].astype(str) != ""]
    grouped = te_scaf.groupby("scaffold").mean(numeric_only=True)
    keep = grouped.index.astype(str).tolist()
    if not keep:
        return {"note": "no evaluable test scaffolds"}

    Xte = _featurize_smiles(keep)
    Yte = np.where(np.isnan(grouped.to_numpy(dtype=np.float32)), np.nan,
                   (grouped.to_numpy(dtype=np.float32) >= 0.5).astype(np.float32))
    prob = model.predict_matrix(Xte)
    metrics = evaluate_multitask(Yte, prob, list(CLASS_KEYS))
    out = {
        "n_train_scaffolds": int(len(table)),
        "n_test_scaffolds": int(len(keep)),
        "per_task": metrics.to_dict(orient="records"),
        "summary": summarize(metrics),
        "summary_by_block": summarize_by_block(metrics),
    }
    log.info("scaffold head (test): %s", out["summary"])
    return out


def train_regression_heads(ds: Dataset, cfg: TrainConfig) -> dict:
    """
    Potency regression: predict pActivity directly, per class and per target.

    The per-target heads are what make selectivity computable - a selectivity
    index is the difference between two predicted potencies, which the binary
    classifiers cannot express. The per-class heads give the optimizer a
    continuous reward, so an edit from 120 nM to 40 nM counts as progress
    instead of registering as "active either way".
    """
    log.info("=== potency regression heads ===")
    tr, te = ds.train_idx, ds.test_idx
    frame = ds.frame
    out: dict = {}

    pclass = frame[[f"pmax_{k}" for k in CLASS_KEYS]].to_numpy(dtype=np.float32)
    ptarget = frame[[f"p_{t}" for t in TARGET_KEYS]].to_numpy(dtype=np.float32)

    # Same reasoning as train_baseline_heads: read the splits out of the memmap
    # once, rather than letting each of the 58 regressors gather scattered rows
    # from a 1.4 GB file.
    X_train = np.ascontiguousarray(ds.X[tr])
    X_test = np.ascontiguousarray(ds.X[te])

    reg_c = BaselineMultiTaskRegressor().fit(X_train, pclass[tr], list(CLASS_KEYS))
    reg_c.save(ART["regression_class"])
    pred_c = reg_c.predict_matrix(X_test)
    mc = evaluate_regression(pclass[te], pred_c, list(CLASS_KEYS))
    out["class"] = {"per_task": mc.to_dict(orient="records"),
                    "summary": summarize_regression(mc)}
    log.info("class potency (test): %s", out["class"]["summary"])

    reg_t = BaselineMultiTaskRegressor().fit(X_train, ptarget[tr], list(TARGET_KEYS))
    reg_t.save(ART["regression_target"])
    pred_t = reg_t.predict_matrix(X_test)
    mt = evaluate_regression(ptarget[te], pred_t, list(TARGET_KEYS))
    out["target"] = {"per_task": mt.to_dict(orient="records"),
                     "summary": summarize_regression(mt)}
    log.info("target potency (test): %s", out["target"]["summary"])
    del X_train, X_test
    gc.collect()
    return out


def fit_applicability(ds: Dataset) -> None:
    ad = ApplicabilityDomain().fit(
        [ds.smiles[i] for i in ds.train_idx], ds.Y_class[ds.train_idx]
    )
    ad.save(ART["ad"])


def compute_fg_table(ds: Dataset) -> pd.DataFrame:
    log.info("=== functional-group enrichment ===")
    table = fg_enrichment.compute_enrichment(
        [ds.smiles[i] for i in ds.train_idx], ds.Y_class[ds.train_idx]
    )
    if not table.empty:
        table.to_parquet(ART["fg"], index=False)
    return table


def mine_mmp_rules(ds: Dataset, cfg: TrainConfig) -> pd.DataFrame:
    log.info("=== matched molecular pairs ===")
    pcols = [f"pmax_{k}" for k in CLASS_KEYS]
    p_matrix = ds.frame.iloc[ds.train_idx][pcols].to_numpy(dtype=np.float32)
    rules = mmp.mine_transformations(
        [ds.smiles[i] for i in ds.train_idx], p_matrix, CLASS_KEYS,
        min_pairs=cfg.mmp_min_pairs,
    )
    if not rules.empty:
        rules.to_parquet(ART["mmp"], index=False)
    return rules


def train_all(ds: Dataset, cfg: TrainConfig | None = None) -> dict:
    cfg = cfg or TrainConfig()
    started = time.time()

    metrics = train_baseline_heads(ds, cfg)

    if cfg.train_scaffold:
        metrics["scaffold"] = train_scaffold_head(ds, cfg)

    if cfg.train_regression:
        metrics["regression"] = train_regression_heads(ds, cfg)

    fit_applicability(ds)
    fg_table = compute_fg_table(ds)
    rules = mine_mmp_rules(ds, cfg) if cfg.train_mmp else pd.DataFrame()

    with open(ART["thresholds"], "w", encoding="utf-8") as fh:
        json.dump(metrics["activity"]["thresholds"], fh, indent=2)
    with open(ART["metrics"], "w", encoding="utf-8") as fh:
        json.dump(metrics, fh, indent=2, default=float)

    prov_path = CURATED_DATASET.parent / "provenance.json"
    provenance = (json.loads(prov_path.read_text(encoding="utf-8"))
                  if prov_path.exists() else {})

    metadata = {
        "n_molecules": int(len(ds.smiles)),
        "provenance": provenance,
        "n_train": int(len(ds.train_idx)),
        "n_valid": int(len(ds.valid_idx)),
        "n_test": int(len(ds.test_idx)),
        "split": "Bemis-Murcko scaffold split",
        "classes": list(CLASS_KEYS),
        "targets": list(TARGET_KEYS),
        "label_thresholds": {
            k: {
                "assay_format": CLASS_BY_KEY[k].assay_format,
                "active_pActivity": CLASS_BY_KEY[k].active_threshold,
                "inactive_pActivity": CLASS_BY_KEY[k].inactive_threshold,
            }
            for k in CLASS_KEYS
        },
        "n_fg_associations": int(len(fg_table)),
        "n_significant_fg_associations": int(fg_table["significant"].sum()) if not fg_table.empty else 0,
        "n_mmp_rules": int(len(rules)),
        "train_config": asdict(cfg),
        "trained_seconds": round(time.time() - started, 1),
        "backend": "baseline (HistGradientBoosting on ECFP4 counts + descriptors)",
    }
    with open(ART["metadata"], "w", encoding="utf-8") as fh:
        json.dump(metadata, fh, indent=2)

    log.info("training complete in %.1f s", time.time() - started)
    return {"metrics": metrics, "metadata": metadata}
