"""
Potency regression heads.

The classification heads answer "is this active?" against a fixed threshold. That
throws away most of what the data contains: a compound at 3 nM and one at 90 nM
are both just "1". These heads predict pActivity (-log10 molar) directly, which
gives three things the binary heads cannot:

  * a potency estimate in the units medicinal chemists actually use;
  * a continuous reward for the optimizer, so an edit that moves a compound from
    120 nM to 40 nM registers as progress instead of "active either way";
  * selectivity, which is a *difference* between two predicted potencies and is
    meaningless without a continuous scale.

Trained only on molecules with a real measurement for that class. Censored
records ('>' relations) are excluded here even though the classifier uses them,
because "weaker than 10 uM" is a bound, not a value, and regressing on the bound
would drag every prediction downward.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

import joblib
import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor

from ..config import RANDOM_SEED

log = logging.getLogger(__name__)

MIN_SAMPLES_PER_TASK = 150


@dataclass
class RegressionTask:
    name: str
    estimator: object
    n_train: int
    mean: float
    std: float


@dataclass
class BaselineMultiTaskRegressor:
    """One gradient-boosting regressor per class, predicting pActivity."""

    task_names: list[str] = field(default_factory=list)
    models: dict[str, RegressionTask] = field(default_factory=dict)

    def fit(
        self,
        X: np.ndarray,
        Y: np.ndarray,
        task_names: list[str],
        *,
        rows: np.ndarray | None = None,
    ) -> "BaselineMultiTaskRegressor":
        self.task_names = list(task_names)
        rows = np.arange(X.shape[0]) if rows is None else np.asarray(rows)

        for j, name in enumerate(task_names):
            y = Y[:, j]
            sel = rows[~np.isnan(y[rows])]
            if len(sel) < MIN_SAMPLES_PER_TASK:
                log.info("skipping regression task %s (%d measured)", name, len(sel))
                continue

            Xt = np.ascontiguousarray(X[sel])
            yt = y[sel].astype(np.float64)
            est = HistGradientBoostingRegressor(
                max_iter=300,
                learning_rate=0.06,
                max_leaf_nodes=31,
                min_samples_leaf=20,
                l2_regularization=1.0,
                early_stopping=True,
                validation_fraction=0.12,
                n_iter_no_change=25,
                loss="absolute_error",  # robust to the long tail of odd potencies
                random_state=RANDOM_SEED,
            )
            est.fit(Xt, yt)
            del Xt
            self.models[name] = RegressionTask(
                name, est, int(len(sel)), float(yt.mean()), float(yt.std())
            )
            log.info("trained regression %s: n=%d mean pAct=%.2f",
                     name, len(sel), yt.mean())
        return self

    def predict_matrix(self, X: np.ndarray) -> np.ndarray:
        out = np.full((X.shape[0], len(self.task_names)), np.nan, dtype=np.float32)
        for j, name in enumerate(self.task_names):
            tm = self.models.get(name)
            if tm is not None:
                out[:, j] = tm.estimator.predict(X)
        return out

    @property
    def trained_tasks(self) -> list[str]:
        return list(self.models.keys())

    def save(self, path) -> None:
        joblib.dump(self, path, compress=3)

    @staticmethod
    def load(path) -> "BaselineMultiTaskRegressor":
        return joblib.load(path)


def p_activity_to_nanomolar(p: float) -> float:
    """pActivity -> nM, the unit an assay report is actually written in."""
    if p is None or np.isnan(p):
        return float("nan")
    return float(10.0 ** (9.0 - p))


def format_potency(p: float) -> str:
    """Human-readable potency with the unit chosen to suit the magnitude."""
    if p is None or np.isnan(p):
        return "n/a"
    nm = p_activity_to_nanomolar(p)
    if nm < 1000:
        return f"{nm:.0f} nM"
    if nm < 1e6:
        return f"{nm / 1000:.1f} uM"
    return f"{nm / 1e6:.1f} mM"
