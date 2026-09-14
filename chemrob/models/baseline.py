"""
Gradient-boosting baseline over ECFP4 counts + physicochemical descriptors.

Every QSAR paper that skips this step and reports only a neural number is
unfalsifiable. The baseline exists so the D-MPNN has to earn its complexity:
`scripts/evaluate.py` prints both side by side on the same scaffold split.

It is also the fallback the application serves when PyTorch is unavailable, and
its per-task probabilities are calibrated, which matters because the optimizer
compares predicted activity between a parent molecule and its analogues.
"""
from __future__ import annotations

import gc
import logging
from dataclasses import dataclass, field

import joblib
import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.linear_model import LogisticRegression

from ..config import MIN_LABELS_PER_CLASS, MIN_POSITIVES_PER_CLASS, RANDOM_SEED

log = logging.getLogger(__name__)

# scikit-learn 1.6 replaced CalibratedClassifierCV(cv="prefit") with an explicit
# FrozenEstimator wrapper, and 1.8 removed the old spelling outright.
try:
    from sklearn.frozen import FrozenEstimator

    def _calibrator(fitted_estimator):
        return CalibratedClassifierCV(FrozenEstimator(fitted_estimator), method="sigmoid")

except ImportError:  # scikit-learn < 1.6

    def _calibrator(fitted_estimator):
        return CalibratedClassifierCV(fitted_estimator, method="sigmoid", cv="prefit")


@dataclass
class TaskModel:
    name: str
    estimator: object
    n_train: int
    n_positive: int
    base_rate: float


@dataclass
class BaselineMultiTask:
    """
    One calibrated binary classifier per task, each trained only on the rows
    where that task actually has a label.
    """
    task_names: list[str] = field(default_factory=list)
    models: dict[str, TaskModel] = field(default_factory=dict)
    kind: str = "hgb"

    def fit(
        self,
        X: np.ndarray,
        Y: np.ndarray,
        task_names: list[str],
        *,
        X_valid: np.ndarray | None = None,
        Y_valid: np.ndarray | None = None,
        rows: np.ndarray | None = None,
        valid_rows: np.ndarray | None = None,
    ) -> "BaselineMultiTask":
        """
        `X` may be a memory-mapped array covering the whole dataset, with `rows`
        selecting the training split. Only the rows a given task has labels for
        are ever materialised, which keeps peak memory to one task's slice
        rather than the full matrix.
        """
        self.task_names = list(task_names)
        rows = np.arange(X.shape[0]) if rows is None else np.asarray(rows)

        for j, name in enumerate(task_names):
            y = Y[:, j]
            labelled = ~np.isnan(y)
            sel = rows[labelled[rows]]
            n = int(len(sel))
            pos = int((y[sel] == 1).sum())
            if n < MIN_LABELS_PER_CLASS or pos < MIN_POSITIVES_PER_CLASS or pos == n:
                log.info("skipping task %s (%d labelled, %d positive)", name, n, pos)
                continue

            Xt = np.ascontiguousarray(X[sel])
            yt = y[sel].astype(int)
            if self.kind == "hgb":
                est = HistGradientBoostingClassifier(
                    max_iter=250,
                    learning_rate=0.08,
                    max_leaf_nodes=31,
                    min_samples_leaf=20,
                    l2_regularization=1.0,
                    early_stopping=True,
                    validation_fraction=0.12,
                    n_iter_no_change=20,
                    class_weight="balanced",
                    random_state=RANDOM_SEED,
                )
            elif self.kind == "rf":
                # Random forest on ECFP4 is the reference point most QSAR
                # readers carry in their head, so it is the comparison that
                # makes an absolute number interpretable.
                est = RandomForestClassifier(
                    n_estimators=200, max_features="sqrt", min_samples_leaf=2,
                    class_weight="balanced_subsample", n_jobs=-1,
                    random_state=RANDOM_SEED,
                )
            else:
                est = LogisticRegression(
                    max_iter=3000, C=0.5, class_weight="balanced",
                    solver="liblinear", random_state=RANDOM_SEED,
                )

            # Calibrate on the held-out validation fold when one is given, so the
            # probabilities the optimizer compares between parent and analogue
            # are on a meaningful scale rather than raw boosting scores.
            est.fit(Xt, yt)
            del Xt
            gc.collect()
            if Y_valid is not None:
                vy = Y_valid[:, j]
                vrows = (
                    np.asarray(valid_rows)[~np.isnan(vy[np.asarray(valid_rows)])]
                    if valid_rows is not None
                    else np.flatnonzero(~np.isnan(vy))
                )
                vsource = X_valid if X_valid is not None else X
                if len(vrows) >= 40 and len(np.unique(vy[vrows])) == 2:
                    cal = _calibrator(est)
                    cal.fit(np.ascontiguousarray(vsource[vrows]), vy[vrows].astype(int))
                    est = cal

            self.models[name] = TaskModel(name, est, n, pos, pos / n)
            log.info("trained %s: n=%d pos=%d (%.1f%%)", name, n, pos, 100 * pos / n)
        return self

    def predict_proba(self, X: np.ndarray) -> dict[str, np.ndarray]:
        return {
            name: tm.estimator.predict_proba(X)[:, 1] for name, tm in self.models.items()
        }

    def predict_matrix(self, X: np.ndarray) -> np.ndarray:
        """(n_samples, n_tasks) with NaN columns for tasks that were never trained."""
        out = np.full((X.shape[0], len(self.task_names)), np.nan, dtype=np.float32)
        probs = self.predict_proba(X)
        for j, name in enumerate(self.task_names):
            if name in probs:
                out[:, j] = probs[name]
        return out

    @property
    def trained_tasks(self) -> list[str]:
        return list(self.models.keys())

    def save(self, path) -> None:
        joblib.dump(self, path, compress=3)

    @staticmethod
    def load(path) -> "BaselineMultiTask":
        return joblib.load(path)
