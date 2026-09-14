"""
Training loop for the multi-task D-MPNN.

The scaffold head is trained on the Bemis-Murcko core of each molecule with that
molecule's own class labels as the target. That is the operational form of the
question Module 2 asks - "given only this ring system, what activity does it
tend to produce?" - and it lets the scaffold head share the encoder's weights
instead of needing a separate network and a separate aggregated dataset.

Graphs are built per batch rather than precomputed: featurising a batch of 128
costs a few milliseconds against a much larger forward/backward, while holding
150k graphs in memory would cost several gigabytes.
"""
from __future__ import annotations

import json
import logging
import time

import numpy as np
import torch
from rdkit import Chem
from torch.optim import AdamW
from torch.optim.lr_scheduler import ReduceLROnPlateau

from .config import ARTIFACT_DIR, CLASS_KEYS, RANDOM_SEED, TARGET_KEYS
from .dataset import Dataset
from .evaluate import evaluate_multitask, summarize
from .featurize import MolGraph
from .models.dmpnn import (
    ChemRobDMPNN,
    collate_graphs,
    compute_pos_weight,
    count_parameters,
    init_output_bias,
    masked_bce,
)
from .scaffold import murcko_scaffold

log = logging.getLogger(__name__)

_EMPTY_GRAPH_SMILES = "C"


# A MolGraph costs roughly 15-20 kB, so caching every molecule in a 170k-set
# would need ~3 GB - more than is available here once the feature matrix and the
# optimizer state are resident. The cache is therefore bounded and simply
# emptied when it fills: rebuilding a graph takes about 0.3 ms against a
# forward/backward pass of tens of milliseconds, so the recomputation is cheap
# and the memory ceiling is guaranteed.
GRAPH_CACHE_LIMIT = 60000


def _graph(smiles: str, cache: dict[str, MolGraph]) -> MolGraph:
    g = cache.get(smiles)
    if g is None:
        mol = Chem.MolFromSmiles(smiles) or Chem.MolFromSmiles(_EMPTY_GRAPH_SMILES)
        g = MolGraph(mol)
        if len(cache) >= GRAPH_CACHE_LIMIT:
            cache.clear()
        cache[smiles] = g
    return g


def _scaffold_smiles(smiles: str, cache: dict[str, str]) -> str:
    s = cache.get(smiles)
    if s is None:
        mol = Chem.MolFromSmiles(smiles)
        s = murcko_scaffold(mol) if mol is not None else ""
        # A molecule with no ring system has no core; fall back to itself so the
        # scaffold head still sees a valid graph rather than an empty one.
        cache[smiles] = s or smiles
        s = cache[smiles]
    return s


def _batches(idx: np.ndarray, batch_size: int, rng: np.random.Generator | None = None):
    order = idx.copy()
    if rng is not None:
        rng.shuffle(order)
    for i in range(0, len(order), batch_size):
        yield order[i : i + batch_size]


@torch.no_grad()
def predict_dmpnn(
    model: ChemRobDMPNN,
    smiles: list[str],
    *,
    batch_size: int = 256,
    device: torch.device | None = None,
) -> tuple[np.ndarray, np.ndarray]:
    """Return (activity probabilities, target probabilities) for a list of SMILES."""
    device = device or torch.device("cpu")
    model.eval()
    cache: dict[str, MolGraph] = {}
    act = np.zeros((len(smiles), model.n_classes), dtype=np.float32)
    tgt = np.zeros((len(smiles), model.n_targets), dtype=np.float32)
    for start in range(0, len(smiles), batch_size):
        chunk = smiles[start : start + batch_size]
        bg = collate_graphs([_graph(s, cache) for s in chunk]).to(device)
        out = model(bg)
        act[start : start + len(chunk)] = torch.sigmoid(out["activity"]).cpu().numpy()
        tgt[start : start + len(chunk)] = torch.sigmoid(out["target"]).cpu().numpy()
    return act, tgt


def train_dmpnn(
    ds: Dataset,
    *,
    epochs: int = 30,
    batch_size: int = 128,
    hidden: int = 256,
    depth: int = 3,
    dropout: float = 0.1,
    lr: float = 8e-4,
    weight_decay: float = 1e-5,
    scaffold_loss_weight: float = 0.4,
    target_loss_weight: float = 0.5,
    patience: int = 6,
    max_train: int | None = None,
    seed: int = RANDOM_SEED,
    device: str = "cpu",
    threads: int | None = 8,
) -> dict:
    torch.manual_seed(seed)
    dev = torch.device(device)
    rng = np.random.default_rng(seed)

    # Molecular graphs are small tensors, so spreading each op across every core
    # costs more in synchronisation than it saves in arithmetic. Capping the
    # thread count measurably speeds up CPU training on this workload and leaves
    # cores free for the RDKit graph construction happening between steps.
    if device == "cpu" and threads:
        torch.set_num_threads(threads)
        log.info("torch threads capped at %d", threads)

    train_idx = ds.train_idx
    if max_train is not None and len(train_idx) > max_train:
        train_idx = rng.choice(train_idx, size=max_train, replace=False)
        log.info("subsampled training set to %d molecules", len(train_idx))

    model = ChemRobDMPNN(
        len(CLASS_KEYS), len(TARGET_KEYS), hidden=hidden, depth=depth, dropout=dropout
    ).to(dev)
    init_output_bias(model.activity_head, ds.Y_class[train_idx])
    init_output_bias(model.target_head, ds.Y_target[train_idx])
    init_output_bias(model.scaffold_head, ds.Y_class[train_idx])
    log.info("D-MPNN: hidden=%d depth=%d params=%d", hidden, depth, count_parameters(model))

    pw_act = compute_pos_weight(ds.Y_class[train_idx]).to(dev)
    pw_tgt = compute_pos_weight(ds.Y_target[train_idx]).to(dev)

    opt = AdamW(model.parameters(), lr=lr, weight_decay=weight_decay)
    sched = ReduceLROnPlateau(opt, mode="max", factor=0.5, patience=2)

    gcache: dict[str, MolGraph] = {}
    scache: dict[str, str] = {}

    Yc = torch.from_numpy(np.nan_to_num(ds.Y_class, nan=0.0))
    Mc = torch.from_numpy((~np.isnan(ds.Y_class)).astype(np.float32))
    Yt = torch.from_numpy(np.nan_to_num(ds.Y_target, nan=0.0))
    Mt = torch.from_numpy((~np.isnan(ds.Y_target)).astype(np.float32))

    valid_smiles = [ds.smiles[i] for i in ds.valid_idx]
    best_score, best_state, bad_epochs = -1.0, None, 0
    history: list[dict] = []

    for epoch in range(1, epochs + 1):
        model.train()
        t0 = time.time()
        losses: list[float] = []
        for batch in _batches(train_idx, batch_size, rng):
            smis = [ds.smiles[i] for i in batch]
            bg = collate_graphs([_graph(s, gcache) for s in smis]).to(dev)
            sbg = collate_graphs(
                [_graph(_scaffold_smiles(s, scache), gcache) for s in smis]
            ).to(dev)

            out = model(bg, scaffold_bg=sbg)
            bi = torch.from_numpy(batch)
            loss = masked_bce(out["activity"], Yc[bi].to(dev), Mc[bi].to(dev), pw_act)
            loss = loss + target_loss_weight * masked_bce(
                out["target"], Yt[bi].to(dev), Mt[bi].to(dev), pw_tgt
            )
            loss = loss + scaffold_loss_weight * masked_bce(
                out["scaffold"], Yc[bi].to(dev), Mc[bi].to(dev), pw_act
            )

            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            losses.append(float(loss.detach()))

        act_prob, _ = predict_dmpnn(model, valid_smiles, device=dev)
        vm = evaluate_multitask(ds.Y_class[ds.valid_idx], act_prob, list(CLASS_KEYS))
        vs = summarize(vm)
        score = vs.get("macro_auprc", 0.0)
        sched.step(score)

        row = {
            "epoch": epoch,
            "train_loss": round(float(np.mean(losses)), 5),
            "valid_macro_auprc": score,
            "valid_macro_auroc": vs.get("macro_auroc", 0.0),
            "seconds": round(time.time() - t0, 1),
            "lr": opt.param_groups[0]["lr"],
        }
        history.append(row)
        log.info(
            "epoch %2d | loss %.4f | valid AUPRC %.4f AUROC %.4f | %.0fs",
            epoch, row["train_loss"], score, row["valid_macro_auroc"], row["seconds"],
        )

        if score > best_score:
            best_score = score
            best_state = {k: v.detach().clone() for k, v in model.state_dict().items()}
            bad_epochs = 0
        else:
            bad_epochs += 1
            if bad_epochs >= patience:
                log.info("early stopping at epoch %d", epoch)
                break

    if best_state is not None:
        model.load_state_dict(best_state)

    test_smiles = [ds.smiles[i] for i in ds.test_idx]
    act_prob, tgt_prob = predict_dmpnn(model, test_smiles, device=dev)
    tm = evaluate_multitask(ds.Y_class[ds.test_idx], act_prob, list(CLASS_KEYS))
    tt = evaluate_multitask(ds.Y_target[ds.test_idx], tgt_prob, list(TARGET_KEYS))

    ckpt = {
        "state_dict": model.state_dict(),
        "config": {
            "hidden": hidden, "depth": depth, "dropout": dropout,
            "n_classes": len(CLASS_KEYS), "n_targets": len(TARGET_KEYS),
            "class_keys": list(CLASS_KEYS), "target_keys": list(TARGET_KEYS),
        },
    }
    torch.save(ckpt, ARTIFACT_DIR / "dmpnn.pt")

    result = {
        "history": history,
        "best_valid_macro_auprc": best_score,
        "test_activity": {"per_task": tm.to_dict(orient="records"), "summary": summarize(tm)},
        "test_target": {"summary": summarize(tt)},
        "n_parameters": count_parameters(model),
    }
    with open(ARTIFACT_DIR / "dmpnn_metrics.json", "w", encoding="utf-8") as fh:
        json.dump(result, fh, indent=2, default=float)
    log.info("D-MPNN test: %s", result["test_activity"]["summary"])
    return result


def load_dmpnn(path=None, device: str = "cpu") -> ChemRobDMPNN | None:
    path = path or (ARTIFACT_DIR / "dmpnn.pt")
    if not path.exists():
        return None
    ckpt = torch.load(path, map_location=device, weights_only=False)
    c = ckpt["config"]
    model = ChemRobDMPNN(
        c["n_classes"], c["n_targets"], hidden=c["hidden"], depth=c["depth"],
        dropout=c["dropout"],
    )
    model.load_state_dict(ckpt["state_dict"])
    model.eval()
    return model
