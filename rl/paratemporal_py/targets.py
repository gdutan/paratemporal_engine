"""
n-step targets  G(s; n, V) = E[ sum_{i<n} gamma^i r_{i+1} + gamma^n V(S_n) | S_0 = s ]  and estimators of it.

Every function returns (estimate, work), where work counts transitions examined or sampled: an exact expansion of
a state costs its number of successors; a sampled transition costs 1.
"""
import numpy as np


def exact(mrp, s, n, V):
    """Exhaustive expansion with merging (weights per state = the merged nodes). Exact; no variance."""
    w = np.zeros(mrp.n_states); w[s] = 1.0
    acc, work, disc = 0.0, 0, 1.0
    for _ in range(n):
        live = (w > 0) & ~mrp.terminal
        if not live.any():
            break
        work += int(mrp.nnz[live].sum())
        acc += disc * float(w[live] @ mrp.r_expected[live])
        w_next = w[live] @ mrp.P[live]
        w = np.where(mrp.terminal, w, 0.0) + w_next      # terminal mass stays put, earns nothing more
        disc *= mrp.gamma
    return acc + disc * float(w[~mrp.terminal] @ V[~mrp.terminal]), work


def budget(mrp, s, n, V, B, rng):
    """
    Sampled expansion with merging and a per-stage budget B: each live merged node i gets
    n_i = max(1, round(B * w_i / W)) fresh samples, each child weight w_i / n_i. Unbiased.
    """
    w = np.zeros(mrp.n_states); w[s] = 1.0
    acc, work, disc = 0.0, 0, 1.0
    for _ in range(n):
        live = np.flatnonzero((w > 0) & ~mrp.terminal)
        if live.size == 0:
            break
        W = w[live].sum()
        counts = np.maximum(1, np.rint(B * w[live] / W)).astype(int)
        parents = np.repeat(live, counts)
        child_w = np.repeat(w[live] / counts, counts)
        nxt, r = mrp.step_many(parents, rng)
        work += parents.size
        acc += disc * float(child_w @ r)
        w_new = np.where(mrp.terminal, w, 0.0)
        np.add.at(w_new, nxt, child_w)
        w = w_new
        disc *= mrp.gamma
    return acc + disc * float(w[~mrp.terminal] @ V[~mrp.terminal]), work


def rollouts(mrp, s, n, V, N, rng):
    """N independent n-step rollouts from s, each weight 1/N. Unbiased."""
    pos = np.full(N, s); g = np.zeros(N); alive = ~mrp.terminal[pos]
    work, disc = 0, 1.0
    for _ in range(n):
        idx = np.flatnonzero(alive)
        if idx.size == 0:
            break
        nxt, r = mrp.step_many(pos[idx], rng)
        work += idx.size
        g[idx] += disc * r
        pos[idx] = nxt
        alive = ~mrp.terminal[pos]
        disc *= mrp.gamma
    boot = np.where(mrp.terminal[pos], 0.0, V[pos])
    return float((g + disc * boot).mean()), work
