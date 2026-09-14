"""
Layer 3 - Multi-task directed message-passing neural network (Chemprop-style).

One shared encoder, three heads, as specified in the architecture slide:

    molecular graph -> D-MPNN encoder -> pooled molecule vector
                                          |-- activity head  (14 classes)
                                          |-- target head    (36 ChEMBL targets)
                                          '-- scaffold head  (14 classes, fed the
                                              Bemis-Murcko core through the same
                                              encoder weights)

The scaffold head shares the encoder rather than owning a second network. That
is the transfer-learning step from Module 2 of the deck: the encoder has already
learned to read chemical graphs, so the core-ring model only needs its own
output layer.

Training uses masked BCE. Cells where a molecule was never assayed contribute
nothing to the gradient, and per-task positive weighting handles the fact that
most activity classes are rare.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from ..featurize import ATOM_FDIM, BOND_FDIM, MolGraph


# --------------------------------------------------------------------------
# Batching
# --------------------------------------------------------------------------
@dataclass
class BatchedGraph:
    f_atoms: torch.Tensor      # (total_atoms, ATOM_FDIM)
    f_edges: torch.Tensor      # (total_edges, ATOM_FDIM + BOND_FDIM)
    edge_src: torch.Tensor     # (total_edges,)
    edge_dst: torch.Tensor     # (total_edges,)
    rev_index: torch.Tensor    # (total_edges,)
    atom_batch: torch.Tensor   # (total_atoms,) molecule index of each atom
    n_mols: int

    def to(self, device: torch.device) -> "BatchedGraph":
        return BatchedGraph(
            self.f_atoms.to(device), self.f_edges.to(device),
            self.edge_src.to(device), self.edge_dst.to(device),
            self.rev_index.to(device), self.atom_batch.to(device), self.n_mols,
        )


def collate_graphs(graphs: list[MolGraph]) -> BatchedGraph:
    """Concatenate molecule graphs into one disconnected graph with offsets."""
    f_atoms, f_edges = [], []
    edge_src, edge_dst, rev_index, atom_batch = [], [], [], []
    atom_off = edge_off = 0
    for i, g in enumerate(graphs):
        f_atoms.append(g.f_atoms)
        atom_batch.append(np.full(g.n_atoms, i, dtype=np.int64))
        if g.n_edges:
            f_edges.append(g.f_edges)
            edge_src.append(g.edge_src + atom_off)
            edge_dst.append(g.edge_dst + atom_off)
            rev_index.append(g.rev_index + edge_off)
        atom_off += g.n_atoms
        edge_off += g.n_edges

    empty_e = np.zeros((0, ATOM_FDIM + BOND_FDIM), dtype=np.float32)
    empty_i = np.zeros(0, dtype=np.int64)
    return BatchedGraph(
        torch.from_numpy(np.concatenate(f_atoms)),
        torch.from_numpy(np.concatenate(f_edges) if f_edges else empty_e),
        torch.from_numpy(np.concatenate(edge_src) if edge_src else empty_i),
        torch.from_numpy(np.concatenate(edge_dst) if edge_dst else empty_i),
        torch.from_numpy(np.concatenate(rev_index) if rev_index else empty_i),
        torch.from_numpy(np.concatenate(atom_batch)),
        len(graphs),
    )


# --------------------------------------------------------------------------
# Encoder
# --------------------------------------------------------------------------
class DMPNNEncoder(nn.Module):
    """
    Directed message passing over bonds.

    Messages live on directed edges, and each update subtracts the reverse edge's
    message so information never bounces straight back the way it came - the
    property that distinguishes D-MPNN from a plain atom-based GNN and the reason
    it handles substituent effects on a ring well.
    """

    def __init__(self, hidden: int = 300, depth: int = 4, dropout: float = 0.1) -> None:
        super().__init__()
        self.hidden, self.depth = hidden, depth
        self.W_i = nn.Linear(ATOM_FDIM + BOND_FDIM, hidden, bias=False)
        self.W_h = nn.Linear(hidden, hidden, bias=False)
        self.W_o = nn.Linear(ATOM_FDIM + hidden, hidden)
        self.dropout = nn.Dropout(dropout)
        # Additive attention over atoms - doubles as the explainability signal.
        self.att = nn.Sequential(nn.Linear(hidden, hidden // 2), nn.Tanh(),
                                 nn.Linear(hidden // 2, 1))

    def forward(self, bg: BatchedGraph, *, return_atom_weights: bool = False):
        device = bg.f_atoms.device
        n_atoms = bg.f_atoms.size(0)

        if bg.f_edges.size(0) == 0:
            # Single-atom / bondless input: fall back to the atom features alone.
            zeros = torch.zeros(n_atoms, self.hidden, device=device)
            h_atom = F.relu(self.W_o(torch.cat([bg.f_atoms, zeros], dim=1)))
        else:
            h0 = F.relu(self.W_i(bg.f_edges))
            h = h0
            for _ in range(self.depth - 1):
                agg = torch.zeros(n_atoms, self.hidden, device=device)
                agg.index_add_(0, bg.edge_dst, h)          # incoming messages per atom
                message = agg[bg.edge_src] - h[bg.rev_index]
                h = F.relu(h0 + self.W_h(message))
                h = self.dropout(h)
            agg = torch.zeros(n_atoms, self.hidden, device=device)
            agg.index_add_(0, bg.edge_dst, h)
            h_atom = F.relu(self.W_o(torch.cat([bg.f_atoms, agg], dim=1)))

        h_atom = self.dropout(h_atom)

        # Attention pooling, computed per molecule with a segment-wise softmax.
        scores = self.att(h_atom).squeeze(-1)
        max_per_mol = torch.full((bg.n_mols,), -1e30, device=device)
        max_per_mol = max_per_mol.scatter_reduce(0, bg.atom_batch, scores, reduce="amax")
        exp_scores = torch.exp(scores - max_per_mol[bg.atom_batch])
        denom = torch.zeros(bg.n_mols, device=device).index_add_(0, bg.atom_batch, exp_scores)
        weights = exp_scores / (denom[bg.atom_batch] + 1e-12)

        attn_pool = torch.zeros(bg.n_mols, self.hidden, device=device)
        attn_pool.index_add_(0, bg.atom_batch, h_atom * weights.unsqueeze(-1))

        sum_pool = torch.zeros(bg.n_mols, self.hidden, device=device)
        sum_pool.index_add_(0, bg.atom_batch, h_atom)
        counts = torch.zeros(bg.n_mols, device=device).index_add_(
            0, bg.atom_batch, torch.ones_like(scores)
        )
        mean_pool = sum_pool / counts.clamp(min=1).unsqueeze(-1)

        mol_vec = torch.cat([attn_pool, mean_pool], dim=1)
        if return_atom_weights:
            return mol_vec, weights
        return mol_vec

    @property
    def out_dim(self) -> int:
        return self.hidden * 2


class Head(nn.Module):
    def __init__(self, in_dim: int, out_dim: int, hidden: int = 256, dropout: float = 0.1):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(in_dim, hidden), nn.ReLU(), nn.Dropout(dropout),
            nn.Linear(hidden, out_dim),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x)


class ChemRobDMPNN(nn.Module):
    """The full multi-task network: shared encoder + three prediction heads."""

    def __init__(
        self,
        n_classes: int,
        n_targets: int,
        *,
        hidden: int = 300,
        depth: int = 4,
        dropout: float = 0.1,
        n_extra_features: int = 0,
    ) -> None:
        super().__init__()
        self.encoder = DMPNNEncoder(hidden=hidden, depth=depth, dropout=dropout)
        self.n_extra_features = n_extra_features
        dim = self.encoder.out_dim + n_extra_features
        self.activity_head = Head(dim, n_classes, dropout=dropout)
        self.target_head = Head(dim, n_targets, dropout=dropout)
        self.scaffold_head = Head(self.encoder.out_dim, n_classes, dropout=dropout)
        self.n_classes, self.n_targets = n_classes, n_targets

    def encode(self, bg: BatchedGraph, extra: torch.Tensor | None = None,
               *, return_atom_weights: bool = False):
        if return_atom_weights:
            vec, w = self.encoder(bg, return_atom_weights=True)
        else:
            vec, w = self.encoder(bg), None
        if extra is not None and self.n_extra_features:
            vec = torch.cat([vec, extra], dim=1)
        return (vec, w) if return_atom_weights else vec

    def forward(
        self,
        bg: BatchedGraph,
        extra: torch.Tensor | None = None,
        scaffold_bg: BatchedGraph | None = None,
    ) -> dict[str, torch.Tensor]:
        vec = self.encode(bg, extra)
        out = {
            "activity": self.activity_head(vec),
            "target": self.target_head(vec),
        }
        if scaffold_bg is not None:
            out["scaffold"] = self.scaffold_head(self.encoder(scaffold_bg))
        return out


# --------------------------------------------------------------------------
# Losses
# --------------------------------------------------------------------------
def masked_bce(
    logits: torch.Tensor,
    targets: torch.Tensor,
    mask: torch.Tensor,
    pos_weight: torch.Tensor | None = None,
) -> torch.Tensor:
    """BCE that ignores never-assayed cells entirely."""
    if mask.sum() == 0:
        return logits.sum() * 0.0
    loss = F.binary_cross_entropy_with_logits(
        logits, targets, reduction="none", pos_weight=pos_weight
    )
    return (loss * mask).sum() / mask.sum().clamp(min=1.0)


def masked_focal(
    logits: torch.Tensor,
    targets: torch.Tensor,
    mask: torch.Tensor,
    *,
    gamma: float = 2.0,
    alpha: float = 0.25,
) -> torch.Tensor:
    """
    Focal loss, the option the deck names for class imbalance. Down-weights the
    huge pile of easy negatives so rare actives keep driving the gradient.
    """
    if mask.sum() == 0:
        return logits.sum() * 0.0
    p = torch.sigmoid(logits)
    ce = F.binary_cross_entropy_with_logits(logits, targets, reduction="none")
    p_t = p * targets + (1 - p) * (1 - targets)
    alpha_t = alpha * targets + (1 - alpha) * (1 - targets)
    loss = alpha_t * (1 - p_t).pow(gamma) * ce
    return (loss * mask).sum() / mask.sum().clamp(min=1.0)


def compute_pos_weight(labels: np.ndarray, cap: float = 20.0) -> torch.Tensor:
    """pos_weight = negatives / positives per task, capped to keep training stable."""
    pos = np.nansum(labels == 1, axis=0).astype(np.float64)
    neg = np.nansum(labels == 0, axis=0).astype(np.float64)
    with np.errstate(divide="ignore", invalid="ignore"):
        w = np.where(pos > 0, neg / np.maximum(pos, 1.0), 1.0)
    return torch.tensor(np.clip(w, 1.0, cap), dtype=torch.float32)


def init_output_bias(module: Head, labels: np.ndarray) -> None:
    """
    Start each output at the task's base rate. Without this the network spends
    its first epochs just learning that actives are rare.
    """
    pos = np.nansum(labels == 1, axis=0).astype(np.float64)
    n = np.nansum(~np.isnan(labels), axis=0).astype(np.float64)
    rate = np.clip(np.where(n > 0, pos / np.maximum(n, 1.0), 0.5), 1e-3, 1 - 1e-3)
    bias = np.log(rate / (1 - rate))
    with torch.no_grad():
        module.net[-1].bias.copy_(torch.tensor(bias, dtype=torch.float32))
        module.net[-1].weight.mul_(0.1)


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters() if p.requires_grad)
