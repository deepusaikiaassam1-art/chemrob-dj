"""
Validation beyond a single scaffold split.

Two things the headline metrics cannot tell you:

**How much of the score is split luck?** One split gives one number with no
error bar. Repeating the scaffold split under different seeds and reporting
mean +/- SD shows which differences between classes are real and which are
noise. A class whose AUPRC moves by 0.05 across seeds cannot be meaningfully
compared with another at 0.02 resolution.

**Would it have worked prospectively?** A scaffold split holds out chemistry at
random. Real use is temporal: the model is trained on what is published now and
applied to what gets made next. Splitting on first publication year - train on
<= cutoff, test on later - is the closest offline estimate of that, and it is
almost always the harsher number. If the scaffold-split and time-split scores
diverge sharply, the scaffold split was optimistic.
"""
from __future__ import annotations

import gc
import logging

import numpy as np
import pandas as pd

from .config import CLASS_KEYS, TEST_FRACTION, VALID_FRACTION
from .dataset import Dataset
from .evaluate import evaluate_multitask, summarize, summarize_by_block
from .models.baseline import BaselineMultiTask
from .scaffold import scaffold_split

log = logging.getLogger(__name__)


def time_split(
    ds: Dataset, cutoff_year: int, *, valid_fraction: float = 0.1, seed: int = 0
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """
    Split on first publication year: train on <= cutoff, test on later.

    Molecules with no year (a small minority, mostly from non-literature
    sources) are placed in training rather than discarded - they are legitimate
    training data, and the only requirement is that nothing in the test set
    predates the cutoff.
    """
    years = ds.frame["first_year"].to_numpy(dtype=np.float32)
    is_future = np.nan_to_num(years, nan=0.0) > cutoff_year

    test_idx = np.flatnonzero(is_future)
    train_pool = np.flatnonzero(~is_future)

    rng = np.random.default_rng(seed)
    perm = rng.permutation(len(train_pool))
    n_valid = int(valid_fraction * len(train_pool))
    valid_idx = np.sort(train_pool[perm[:n_valid]])
    train_idx = np.sort(train_pool[perm[n_valid:]])

    log.info(
        "time split at %d: train=%d valid=%d test=%d (%.1f%% of data is post-cutoff)",
        cutoff_year, len(train_idx), len(valid_idx), len(test_idx),
        100 * len(test_idx) / max(len(years), 1),
    )
    return train_idx, valid_idx, test_idx


def year_coverage(ds: Dataset) -> pd.DataFrame:
    """How the dataset is distributed over time - read this before choosing a cutoff."""
    years = ds.frame["first_year"]
    known = years.dropna()
    if known.empty:
        return pd.DataFrame([{"note": "no year information in this dataset"}])
    counts = known.astype(int).value_counts().sort_index()
    cum = counts.cumsum() / counts.sum()
    return pd.DataFrame({
        "year": counts.index,
        "n_molecules": counts.to_numpy(),
        "cumulative_fraction": cum.round(4).to_numpy(),
    })


def _fit_and_score(
    ds: Dataset,
    train_idx: np.ndarray,
    valid_idx: np.ndarray,
    test_idx: np.ndarray,
) -> pd.DataFrame:
    """Train the activity head on one split and return its per-class metrics."""
    X_train = np.ascontiguousarray(ds.X[train_idx])
    X_valid = np.ascontiguousarray(ds.X[valid_idx])
    X_test = np.ascontiguousarray(ds.X[test_idx])
    try:
        model = BaselineMultiTask().fit(
            X_train, ds.Y_class[train_idx], list(CLASS_KEYS),
            X_valid=X_valid, Y_valid=ds.Y_class[valid_idx],
        )
        prob = model.predict_matrix(X_test)
        return evaluate_multitask(ds.Y_class[test_idx], prob, list(CLASS_KEYS))
    finally:
        del X_train, X_valid, X_test
        gc.collect()


def multiseed_scaffold(ds: Dataset, seeds: list[int]) -> dict:
    """
    Repeat the scaffold split under several seeds.

    Note this resamples the *split*, not the data. It measures sensitivity to
    which scaffolds land in the test set, which is the dominant source of
    variance here; it is not a bootstrap confidence interval on the population.
    """
    per_seed: list[pd.DataFrame] = []
    summaries: list[dict] = []

    for seed in seeds:
        log.info("--- scaffold split seed %d ---", seed)
        tr, va, te = scaffold_split(
            ds.smiles, 1.0 - VALID_FRACTION - TEST_FRACTION, VALID_FRACTION, seed=seed
        )
        m = _fit_and_score(ds, np.asarray(tr), np.asarray(va), np.asarray(te))
        m["seed"] = seed
        per_seed.append(m)
        s = summarize(m)
        s["seed"] = seed
        summaries.append(s)
        log.info("seed %d: macro AUROC %.4f, macro AUPRC %.4f",
                 seed, s.get("macro_auroc", float("nan")),
                 s.get("macro_auprc", float("nan")))

    allm = pd.concat(per_seed, ignore_index=True)
    agg = (
        allm.dropna(subset=["auroc"])
        .groupby("task")
        .agg(
            n_seeds=("auroc", "size"),
            auroc_mean=("auroc", "mean"), auroc_sd=("auroc", "std"),
            auprc_mean=("auprc", "mean"), auprc_sd=("auprc", "std"),
            f1_mean=("f1", "mean"), f1_sd=("f1", "std"),
            mean_n=("n", "mean"),
        )
        .reset_index()
    )
    for c in ("auroc_sd", "auprc_sd", "f1_sd"):
        agg[c] = agg[c].fillna(0.0)

    sdf = pd.DataFrame(summaries)
    headline = {
        "seeds": seeds,
        "macro_auroc_mean": round(float(sdf["macro_auroc"].mean()), 4),
        "macro_auroc_sd": round(float(sdf["macro_auroc"].std(ddof=1)), 4) if len(sdf) > 1 else 0.0,
        "macro_auprc_mean": round(float(sdf["macro_auprc"].mean()), 4),
        "macro_auprc_sd": round(float(sdf["macro_auprc"].std(ddof=1)), 4) if len(sdf) > 1 else 0.0,
    }
    return {
        "headline": headline,
        "per_class": agg.round(4).to_dict(orient="records"),
        "per_seed_summary": summaries,
    }


def time_split_evaluation(ds: Dataset, cutoff_year: int) -> dict:
    """Train on literature up to `cutoff_year`, test on everything published later."""
    tr, va, te = time_split(ds, cutoff_year)
    if len(te) < 200:
        return {
            "cutoff_year": cutoff_year,
            "error": f"only {len(te)} molecules after {cutoff_year}; "
                     "choose an earlier cutoff",
        }
    m = _fit_and_score(ds, tr, va, te)
    return {
        "cutoff_year": cutoff_year,
        "n_train": int(len(tr)),
        "n_test": int(len(te)),
        "per_task": m.to_dict(orient="records"),
        "summary": summarize(m),
        # Without this the pooled summary is the only thing on offer, and
        # compare_splits_by_block silently returns {} because it has nothing to
        # read - which is exactly how the report ended up with a blank optimism
        # figure. The blocks lose different amounts under a time split, so this
        # is the half that gets quoted.
        "summary_by_block": summarize_by_block(m),
    }


def compare_splits(scaffold_summary: dict, time_summary: dict) -> dict:
    """
    The honest headline: how much optimism the scaffold split carries.

    A large positive gap means held-out *scaffolds* were easier than held-out
    *years*, i.e. the model benefits from chemistry that had already been
    explored by the time the test compounds were made.
    """
    if "summary" not in time_summary:
        return {}
    out = {}
    for k in ("macro_auroc", "macro_auprc", "macro_f1", "macro_mcc"):
        a, b = scaffold_summary.get(k), time_summary["summary"].get(k)
        if a is not None and b is not None:
            out[k] = {
                "scaffold_split": a,
                "time_split": b,
                "optimism": round(a - b, 4),
            }
    return out


def compare_splits_by_block(scaffold_by_block: dict, time_summary: dict) -> dict:
    """
    The same optimism, computed inside each head group instead of across both.

    The pooled figure blends 14 therapeutic heads with 7 liability heads whose
    base rates sit near 0.84, and the two blocks lose different amounts under a
    time split. Quoting the blended number next to a therapeutic-only table is a
    mistake that has already been made once in this project, so the per-block
    figures are written alongside it and the reporting code reads these.
    """
    tb = (time_summary or {}).get("summary_by_block") or {}
    out: dict = {}
    for block in ("therapeutic", "liability"):
        a_blk, b_blk = scaffold_by_block.get(block), tb.get(block)
        if not a_blk or not b_blk:
            continue
        rows = {}
        for k in ("macro_auroc", "macro_auprc", "macro_f1", "macro_mcc"):
            a, b = a_blk.get(k), b_blk.get(k)
            if a is not None and b is not None:
                rows[k] = {
                    "scaffold_split": a,
                    "time_split": b,
                    "optimism": round(a - b, 4),
                }
        if rows:
            rows["n_tasks"] = a_blk.get("n_tasks_evaluated")
            out[block] = rows
    return out
