"""
Prediction learners shared by the toy-problem experiments.

Each learner runs a fixed number of episodes and returns (mean error over episodes, work per episode), where the
error is supplied by the caller and work counts transitions examined or sampled, including the agent's own steps.
"""
import numpy as np

from . import targets


def ntd(mrp, n, alpha, rng, episodes, start_fn, err_fn):
    """Sutton & Barto's n-step TD (model-free): the n-step return of the agent's own trajectory."""
    V = np.zeros(mrp.n_states); errs, work = [], 0
    for _ in range(episodes):
        S = [start_fn(rng)]; R = [0.0]; T = np.inf; t = 0
        if mrp.terminal[S[0]]:
            errs.append(err_fn(V)); continue
        while True:
            if t < T:
                s2, r = mrp.step(S[t], rng); work += 1
                S.append(s2); R.append(r)
                if mrp.terminal[s2]:
                    T = t + 1
            tau = t - n + 1
            if tau >= 0:
                G = sum(mrp.gamma ** (i - tau - 1) * R[i] for i in range(tau + 1, int(min(tau + n, T)) + 1))
                if tau + n < T:
                    G += mrp.gamma ** n * V[S[tau + n]]
                V[S[tau]] += alpha * (G - V[S[tau]])
            if tau == T - 1:
                break
            t += 1
        errs.append(err_fn(V))
    return float(np.mean(errs)), work / episodes


def model_target(mrp, n, alpha, rng, episodes, start_fn, err_fn, kind, budget=4, max_steps=None):
    """Update V(S_t) at every visited state with a model-computed n-step target (exact, budget or rollouts)."""
    V = np.zeros(mrp.n_states); errs, work = [], 0
    for _ in range(episodes):
        s = start_fn(rng); steps = 0
        while not mrp.terminal[s] and (max_steps is None or steps < max_steps):
            if kind == "exact":
                g, w = targets.exact(mrp, s, n, V)
            elif kind == "budget":
                g, w = targets.budget(mrp, s, n, V, budget, rng)
            else:
                g, w = targets.rollouts(mrp, s, n, V, budget, rng)
            V[s] += alpha * (g - V[s]); work += w
            s, _ = mrp.step(s, rng); work += 1; steps += 1
        errs.append(err_fn(V))
    return float(np.mean(errs)), work / episodes
