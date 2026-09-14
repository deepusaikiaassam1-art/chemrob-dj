"""
Dataset assembly: load the curated table, build features, and split by scaffold.

Feature construction is cached to .npy because featurising ~10^5 molecules costs
minutes and gets repeated across every training run and every evaluation sweep.
"""
from __future__ import annotations

import hashlib
import logging
from dataclasses import dataclass

import numpy as np
import pandas as pd
from rdkit import Chem

from .config import (
    CLASS_KEYS,
    CURATED_DATASET,
    DATA_DIR,
    RANDOM_SEED,
    TARGET_KEYS,
    TEST_FRACTION,
    VALID_FRACTION,
)
from .featurize import FEATURE_DIM, featurize
from .scaffold import scaffold_split

log = logging.getLogger(__name__)

CLASS_COLS = [f"class_{k}" for k in CLASS_KEYS]
TARGET_COLS = [f"target_{t}" for t in TARGET_KEYS]


@dataclass
class Dataset:
    frame: pd.DataFrame
    smiles: list[str]
    scaffolds: list[str]
    X: np.ndarray                # (n, FEATURE_DIM) baseline features
    Y_class: np.ndarray          # (n, 14) with NaN for unlabelled
    Y_target: np.ndarray         # (n, 36) with NaN for unlabelled
    train_idx: np.ndarray
    valid_idx: np.ndarray
    test_idx: np.ndarray

    def split(self, name: str) -> np.ndarray:
        return {"train": self.train_idx, "valid": self.valid_idx, "test": self.test_idx}[name]

    def subset(self, idx: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        return self.X[idx], self.Y_class[idx], self.Y_target[idx]


def _feature_cache_path(smiles: list[str]):
    """
    Cache filename keyed on the *content* of the molecule list, not just its
    length. Keying on row count alone is a trap on exactly the workflow this
    pipeline is built for: rebuild the dataset from refreshed ChEMBL data, land
    on a coincidentally identical molecule count, and the stale feature matrix
    is silently reused against the new labels. The hash makes that impossible -
    different molecules simply miss the cache.
    """
    digest = hashlib.sha1("\n".join(smiles).encode("utf-8")).hexdigest()[:16]
    # Raw float32 rather than .npy: the shape is fully determined by the
    # filename, so no header is needed, and the file can be written with plain
    # buffered sequential I/O. See build_features for why that matters.
    return DATA_DIR / f"features_{len(smiles)}_{FEATURE_DIM}_{digest}.f32"


def stale_feature_caches(keep=None) -> list:
    """Feature caches that no longer match the current dataset, with their sizes."""
    keep = keep.name if keep is not None else ""
    out = []
    for pattern in (f"features_*_{FEATURE_DIM}_*.f32", f"features_*_{FEATURE_DIM}*.npy"):
        out.extend(p for p in DATA_DIR.glob(pattern) if p.name != keep)
    return out


def build_features(
    smiles: list[str], *, use_cache: bool = True, mmap: bool = True
) -> np.ndarray:
    """
    Build (or load) the baseline feature matrix.

    At ~180k molecules x 2081 features the dense matrix is ~1.5 GB, which is
    more than this pipeline should hold resident while gradient boosting is also
    allocating. It is therefore written to disk once and memory-mapped back, so
    only the rows a given task actually needs are ever copied into RAM.
    """
    n = len(smiles)
    cache = _feature_cache_path(smiles)
    expected_bytes = n * FEATURE_DIM * 4

    if use_cache and cache.exists() and cache.stat().st_size == expected_bytes:
        log.info("loaded cached features (%d, %d) (mmap=%s)", n, FEATURE_DIM, mmap)
        mode = "r" if mmap else None
        if mode is None:
            return np.fromfile(cache, dtype=np.float32).reshape(n, FEATURE_DIM)
        return np.memmap(cache, dtype=np.float32, mode="r", shape=(n, FEATURE_DIM))

    log.info("featurising %d molecules", n)
    if not use_cache:
        X = np.zeros((n, FEATURE_DIM), dtype=np.float32)
        for i, smi in enumerate(smiles):
            mol = Chem.MolFromSmiles(smi)
            if mol is not None:
                X[i] = featurize(mol)
        return X

    # Build in RAM-sized chunks and append each with one buffered sequential
    # write, rather than assigning row-by-row into a memory-mapped file.
    #
    # The memmap version dirties a page per molecule across a multi-gigabyte
    # mapping. Once the file no longer fits in the page cache - which on a 16 GB
    # machine with other applications running happens partway through - the OS
    # starts writing back and re-faulting pages continuously, and on Windows the
    # on-access virus scanner re-examines the file on every flush. Measured on
    # this dataset, throughput collapsed from ~10k molecules per 45 s to 10k per
    # 73 min at around the 100k mark. Chunked sequential writes keep the resident
    # set at one chunk and hand the OS large contiguous blocks.
    #
    # Written to a temporary file and renamed only on completion, so an
    # interrupted run cannot leave a half-written cache that the size check above
    # would then accept.
    tmp = cache.with_suffix(".partial")
    chunk_rows = max(1, min(20000, n))
    buf = np.zeros((chunk_rows, FEATURE_DIM), dtype=np.float32)
    written = 0
    with open(tmp, "wb", buffering=1024 * 1024) as fh:
        for start in range(0, n, chunk_rows):
            block = smiles[start : start + chunk_rows]
            buf[: len(block)] = 0.0
            for k, smi in enumerate(block):
                mol = Chem.MolFromSmiles(smi)
                if mol is not None:
                    buf[k] = featurize(mol)
            fh.write(buf[: len(block)].tobytes())
            written += len(block)
            log.info("  %d / %d", written, n)
    del buf

    tmp.replace(cache)
    stale = stale_feature_caches(keep=cache)
    if stale:
        mb = sum(p.stat().st_size for p in stale) / 1e6
        log.warning(
            "%d stale feature cache(s) left in %s using %.0f MB; safe to delete: %s",
            len(stale), DATA_DIR, mb, ", ".join(p.name for p in stale),
        )
    if mmap:
        return np.memmap(cache, dtype=np.float32, mode="r", shape=(n, FEATURE_DIM))
    return np.fromfile(cache, dtype=np.float32).reshape(n, FEATURE_DIM)


def _columns_or_nan(df: pd.DataFrame, cols: list[str], kind: str) -> np.ndarray:
    """Select `cols` in order, substituting an all-NaN column for any that are absent."""
    missing = [c for c in cols if c not in df.columns]
    if missing:
        log.warning(
            "%d %s column(s) absent from the curated dataset and treated as "
            "never-measured: %s. Rebuild with scripts/build_dataset.py to train them.",
            len(missing), kind,
            ", ".join(m.split("_", 1)[1] for m in missing),
        )
    out = np.full((len(df), len(cols)), np.nan, dtype=np.float32)
    for j, c in enumerate(cols):
        if c in df.columns:
            out[:, j] = df[c].to_numpy(dtype=np.float32)
    return out


def load_dataset(
    path=CURATED_DATASET,
    *,
    min_labels: int = 1,
    use_cache: bool = True,
    seed: int = RANDOM_SEED,
) -> Dataset:
    """Load the curated parquet and produce a ready-to-train Dataset."""
    df = pd.read_parquet(path)
    log.info("curated dataset: %d molecules", len(df))

    # A target added to config.py is not in a parquet curated before that edit.
    # Rather than failing to load, the missing columns are filled with NaN -
    # "never measured" - which the masked loss already knows to ignore and the
    # minimum-label guard already knows to skip. The new target simply stays
    # untrained until the data is rebuilt, and everything else keeps working.
    Y_class = _columns_or_nan(df, CLASS_COLS, "activity class")
    Y_target = _columns_or_nan(df, TARGET_COLS, "target")

    # Drop molecules that ended up with no usable label at all (every
    # measurement fell in the ambiguous 1-10 uM band).
    labelled = (~np.isnan(Y_class)).sum(axis=1) >= min_labels
    if not labelled.all():
        log.info("dropping %d molecules with no usable class label", int((~labelled).sum()))
        df = df[labelled].reset_index(drop=True)
        Y_class, Y_target = Y_class[labelled], Y_target[labelled]

    smiles = df["smiles"].astype(str).tolist()
    scaffolds = df["scaffold"].astype(str).tolist()
    X = build_features(smiles, use_cache=use_cache)

    train_idx, valid_idx, test_idx = scaffold_split(
        smiles, 1.0 - VALID_FRACTION - TEST_FRACTION, VALID_FRACTION, seed=seed
    )
    log.info("scaffold split: train=%d valid=%d test=%d",
             len(train_idx), len(valid_idx), len(test_idx))

    return Dataset(
        frame=df, smiles=smiles, scaffolds=scaffolds, X=X,
        Y_class=Y_class, Y_target=Y_target,
        train_idx=np.asarray(train_idx), valid_idx=np.asarray(valid_idx),
        test_idx=np.asarray(test_idx),
    )


def scaffold_level_labels(ds: Dataset, min_members: int = 3, agree: float = 0.5) -> pd.DataFrame:
    """
    Build the training table for the core-ring classifier (Module 2).

    A scaffold is labelled active for a class when at least `agree` of its
    labelled members are active and it has at least `min_members` of them. That
    is the operational meaning of a "privileged scaffold": the core carries the
    activity often enough that it is worth keeping during optimization.
    """
    rows: list[dict] = []
    frame = ds.frame
    for scaf, group in frame.groupby("scaffold"):
        if not scaf:
            continue
        idx = group.index.to_numpy()
        rec: dict = {"scaffold": scaf, "n_members": len(idx)}
        keep = False
        for j, key in enumerate(CLASS_KEYS):
            col = ds.Y_class[idx, j]
            lab = col[~np.isnan(col)]
            if len(lab) < min_members:
                rec[f"class_{key}"] = np.nan
                continue
            rec[f"class_{key}"] = float(lab.mean() >= agree)
            rec[f"n_{key}"] = int(len(lab))
            keep = True
        if keep:
            rows.append(rec)
    out = pd.DataFrame(rows)
    log.info("scaffold-level table: %d scaffolds with >=%d labelled members",
             len(out), min_members)
    return out
