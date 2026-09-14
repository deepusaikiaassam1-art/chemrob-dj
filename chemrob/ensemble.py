"""
Blending the gradient-boosting baseline with the D-MPNN.

The two models fail differently. Boosting on ECFP4 is excellent at recognising
chemistry close to what it has seen, because a fingerprint bit either matches or
does not. A message-passing network generalises across the graph and can carry
signal to scaffolds the fingerprint has no bit for. Averaging them usually beats
either, and where it does not, the weight search finds that too.

The blend weight is fitted **per class on the validation split**, never on test.
Classes where the graph network adds nothing get weight 0 and the ensemble
silently collapses back to the baseline for them, which is the correct outcome
rather than a failure.
"""
from __future__ import annotations

import json
import logging

import numpy as np
from sklearn.metrics import average_precision_score

log = logging.getLogger(__name__)

WEIGHT_GRID = np.linspace(0.0, 1.0, 21)


def _logit(p: np.ndarray, eps: float = 1e-6) -> np.ndarray:
    p = np.clip(p, eps, 1 - eps)
    return np.log(p / (1 - p))


def _sigmoid(z: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-z))


def blend(p_base: np.ndarray, p_other: np.ndarray, w: float, *, in_logit: bool = True) -> np.ndarray:
    """
    Combine two probability matrices, `w` being the weight on `p_other`.

    Averaging in logit space rather than probability space keeps the blend from
    being dominated by whichever model is more confident: two models at 0.99 and
    0.5 average to 0.745 in probability space but to a more moderate value in
    logit space, which is better behaved when only one model is calibrated.
    """
    if w <= 0:
        return p_base
    if w >= 1:
        return p_other
    if in_logit:
        return _sigmoid((1 - w) * _logit(p_base) + w * _logit(p_other))
    return (1 - w) * p_base + w * p_other


def fit_weights(
    Y_valid: np.ndarray,
    P_base: np.ndarray,
    P_other: np.ndarray,
    task_names: list[str],
    *,
    min_labels: int = 40,
) -> dict[str, float]:
    """Per-class blend weight maximising validation AUPRC."""
    weights: dict[str, float] = {}
    for j, name in enumerate(task_names):
        y = Y_valid[:, j]
        keep = ~np.isnan(y) & ~np.isnan(P_base[:, j]) & ~np.isnan(P_other[:, j])
        if keep.sum() < min_labels or len(np.unique(y[keep])) < 2:
            weights[name] = 0.0
            continue
        yt = y[keep].astype(int)
        best_w, best_score = 0.0, -1.0
        for w in WEIGHT_GRID:
            p = blend(P_base[keep, j], P_other[keep, j], float(w))
            score = average_precision_score(yt, p)
            if score > best_score:
                best_score, best_w = score, float(w)
        weights[name] = best_w
        log.info("%s: blend weight %.2f (valid AUPRC %.4f)", name, best_w, best_score)
    return weights


def apply_weights(
    P_base: np.ndarray, P_other: np.ndarray, weights: dict[str, float],
    task_names: list[str],
) -> np.ndarray:
    out = P_base.copy()
    for j, name in enumerate(task_names):
        w = float(weights.get(name, 0.0))
        if w > 0:
            out[:, j] = blend(P_base[:, j], P_other[:, j], w)
    return out


def save_weights(weights: dict[str, float], path) -> None:
    path.write_text(json.dumps(weights, indent=2), encoding="utf-8")


def load_weights(path) -> dict[str, float] | None:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))
