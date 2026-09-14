"""
Evaluation metrics for sparse multi-label bioactivity prediction.

Every metric here is computed per task over only the cells that carry a label,
then reported both per task and as a support-weighted mean. Averaging over tasks
without weighting lets a tiny 60-molecule task swing the headline number as much
as a 30,000-molecule one.

AUPRC is reported alongside AUROC and is the number to read: with a 5% active
rate, AUROC flatters a model that is useless in the region that matters.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.metrics import (
    average_precision_score,
    balanced_accuracy_score,
    f1_score,
    matthews_corrcoef,
    precision_score,
    recall_score,
    roc_auc_score,
)


def task_metrics(y_true: np.ndarray, y_prob: np.ndarray, threshold: float = 0.5) -> dict:
    """Metrics for one task; assumes NaNs are already removed."""
    if len(y_true) == 0 or len(np.unique(y_true)) < 2:
        return {"n": int(len(y_true)), "n_positive": int(np.sum(y_true == 1))}
    y_pred = (y_prob >= threshold).astype(int)
    base = float(np.mean(y_true == 1))
    ap = float(average_precision_score(y_true, y_prob))
    return {
        "n": int(len(y_true)),
        "n_positive": int(np.sum(y_true == 1)),
        "positive_rate": round(base, 4),
        "auroc": round(float(roc_auc_score(y_true, y_prob)), 4),
        "auprc": round(ap, 4),
        # AUPRC only means something relative to the base rate, so carry the lift.
        "auprc_lift": round(ap / base, 3) if base > 0 else float("nan"),
        "f1": round(float(f1_score(y_true, y_pred, zero_division=0)), 4),
        "precision": round(float(precision_score(y_true, y_pred, zero_division=0)), 4),
        "recall": round(float(recall_score(y_true, y_pred, zero_division=0)), 4),
        "balanced_accuracy": round(float(balanced_accuracy_score(y_true, y_pred)), 4),
        "mcc": round(float(matthews_corrcoef(y_true, y_pred)), 4),
    }


def evaluate_multitask(
    Y_true: np.ndarray,
    Y_prob: np.ndarray,
    task_names: list[str],
    threshold: float = 0.5,
) -> pd.DataFrame:
    """Per-task metric table; tasks with no usable labels are reported as such."""
    rows = []
    for j, name in enumerate(task_names):
        yt, yp = Y_true[:, j], Y_prob[:, j]
        keep = ~np.isnan(yt) & ~np.isnan(yp)
        m = task_metrics(yt[keep].astype(int), yp[keep], threshold)
        m["task"] = name
        rows.append(m)
    df = pd.DataFrame(rows)
    cols = ["task"] + [c for c in df.columns if c != "task"]
    return df[cols]


def summarize(metrics: pd.DataFrame) -> dict:
    """Support-weighted headline numbers across evaluable tasks."""
    ok = metrics.dropna(subset=["auroc"]) if "auroc" in metrics.columns else metrics.iloc[0:0]
    if ok.empty:
        return {"n_tasks_evaluated": 0}
    w = ok["n"].to_numpy(dtype=float)
    out = {
        "n_tasks_evaluated": int(len(ok)),
        "n_labels": int(w.sum()),
        "macro_auroc": round(float(ok["auroc"].mean()), 4),
        "macro_auprc": round(float(ok["auprc"].mean()), 4),
        "weighted_auroc": round(float(np.average(ok["auroc"], weights=w)), 4),
        "weighted_auprc": round(float(np.average(ok["auprc"], weights=w)), 4),
        "macro_f1": round(float(ok["f1"].mean()), 4),
        "macro_mcc": round(float(ok["mcc"].mean()), 4),
        "mean_auprc_lift": round(float(ok["auprc_lift"].mean()), 3),
    }
    return out


def best_thresholds(Y_true: np.ndarray, Y_prob: np.ndarray, task_names: list[str]) -> dict:
    """
    Per-task decision threshold maximising F1 on the validation split.

    A single global 0.5 is wrong when active rates range from 2% to 40%; these
    thresholds are stored with the model and used at inference.
    """
    out: dict[str, float] = {}
    grid = np.linspace(0.05, 0.95, 91)
    for j, name in enumerate(task_names):
        yt, yp = Y_true[:, j], Y_prob[:, j]
        keep = ~np.isnan(yt) & ~np.isnan(yp)
        yt, yp = yt[keep].astype(int), yp[keep]
        if len(yt) < 30 or len(np.unique(yt)) < 2:
            out[name] = 0.5
            continue
        scores = [f1_score(yt, (yp >= t).astype(int), zero_division=0) for t in grid]
        out[name] = float(grid[int(np.argmax(scores))])
    return out


# --------------------------------------------------------------------------
# Regression metrics (potency heads)
# --------------------------------------------------------------------------
def regression_task_metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
    """
    Metrics for one potency task.

    RMSE is in log units, so 0.5 means the typical prediction is off by about
    3-fold in potency and 1.0 means 10-fold. Spearman is reported alongside R2
    because for lead optimization the ranking of a congeneric series matters
    more than the absolute value.
    """
    from scipy.stats import spearmanr
    from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

    n = int(len(y_true))
    if n < 10:
        return {"n": n}
    rmse = float(np.sqrt(mean_squared_error(y_true, y_pred)))
    out = {
        "n": n,
        "rmse": round(rmse, 4),
        "mae": round(float(mean_absolute_error(y_true, y_pred)), 4),
        "r2": round(float(r2_score(y_true, y_pred)), 4),
        "fold_error": round(float(10.0 ** rmse), 2),
        "observed_std": round(float(np.std(y_true)), 4),
    }
    if len(np.unique(y_true)) > 2:
        rho, _ = spearmanr(y_true, y_pred)
        out["spearman"] = round(float(rho), 4)
    return out


def evaluate_regression(
    Y_true: np.ndarray, Y_pred: np.ndarray, task_names: list[str]
) -> pd.DataFrame:
    rows = []
    for j, name in enumerate(task_names):
        yt, yp = Y_true[:, j], Y_pred[:, j]
        keep = ~np.isnan(yt) & ~np.isnan(yp)
        m = regression_task_metrics(yt[keep], yp[keep])
        m["task"] = name
        rows.append(m)
    df = pd.DataFrame(rows)
    cols = ["task"] + [c for c in df.columns if c != "task"]
    return df[cols]


def summarize_regression(metrics: pd.DataFrame) -> dict:
    ok = metrics.dropna(subset=["rmse"]) if "rmse" in metrics.columns else metrics.iloc[0:0]
    if ok.empty:
        return {"n_tasks_evaluated": 0}
    w = ok["n"].to_numpy(dtype=float)
    out = {
        "n_tasks_evaluated": int(len(ok)),
        "n_measurements": int(w.sum()),
        "macro_rmse": round(float(ok["rmse"].mean()), 4),
        "weighted_rmse": round(float(np.average(ok["rmse"], weights=w)), 4),
        "macro_mae": round(float(ok["mae"].mean()), 4),
        "macro_r2": round(float(ok["r2"].mean()), 4),
        "median_fold_error": round(float(ok["fold_error"].median()), 2),
    }
    if "spearman" in ok.columns and ok["spearman"].notna().any():
        out["macro_spearman"] = round(float(ok["spearman"].dropna().mean()), 4)
    return out


def summarize_by_block(metrics: pd.DataFrame) -> dict:
    """
    Separate summaries for therapeutic and liability heads.

    Averaging the two together inflates the headline and misleads. The liability
    heads sit at base rates of 0.71-0.95, so their AUPRC is high for arithmetic
    reasons rather than chemical ones - pgp_efflux reaches 0.970 AUPRC at a lift
    of 1.02, meaning it predicts "yes" and is usually right without having
    learned anything. Reporting AUPRC lift over base rate, and MCC, alongside
    the raw value is what makes that visible.
    """
    from .config import LIABILITY_CLASS_KEYS

    out: dict = {}
    is_liab = metrics["task"].isin(LIABILITY_CLASS_KEYS)
    for name, sel in (("therapeutic", metrics[~is_liab]),
                      ("liability", metrics[is_liab])):
        if sel.empty:
            continue
        block = summarize(sel)
        ok = sel.dropna(subset=["auroc"]) if "auroc" in sel.columns else sel.iloc[0:0]
        if not ok.empty and "auprc_lift" in ok.columns:
            block["min_auprc_lift"] = round(float(ok["auprc_lift"].min()), 3)
            block["median_base_rate"] = round(float(ok["positive_rate"].median()), 3)
        block["tasks"] = sorted(sel["task"].tolist())
        out[name] = block
    return out
