"""Tabular Markov reward processes with a known model (dense matrices; fine for tens to hundreds of states)."""
from dataclasses import dataclass

import numpy as np


@dataclass
class TabularMRP:
    """
    P[s, s']  transition probabilities (rows of terminal states are zero: nothing follows termination)
    R[s, s']  reward received on the transition s -> s'
    terminal  boolean mask of terminal states
    start     start state for episodes
    """
    P: np.ndarray
    R: np.ndarray
    terminal: np.ndarray
    start: int
    gamma: float = 1.0
    true_v: np.ndarray = None

    def __post_init__(self):
        self.n_states = self.P.shape[0]
        self.r_expected = (self.P * self.R).sum(axis=1)       # E[r | s]
        self.cum_p = np.cumsum(self.P, axis=1)                # for sampling
        live = ~self.terminal
        if not np.allclose(self.P[live].sum(axis=1), 1.0):
            raise ValueError("rows of nonterminal states must sum to 1")
        self.nnz = (self.P > 0).sum(axis=1)                   # successors per state (work per exact expansion)

    def step(self, s, rng):
        """Sample one transition: returns (s_next, reward)."""
        s2 = int(np.searchsorted(self.cum_p[s], rng.random(), side="right"))
        s2 = min(s2, self.n_states - 1)
        return s2, self.R[s, s2]

    def step_many(self, states, rng):
        """Vectorised sampling for an array of nonterminal states."""
        u = rng.random(len(states))
        nxt = (self.cum_p[states] <= u[:, None]).sum(axis=1)
        nxt = np.minimum(nxt, self.n_states - 1)
        return nxt, self.R[states, nxt]

    def episode(self, rng, start=None):
        """One episode from start: lists of states (S_0..S_T) and rewards (R_1..R_T)."""
        s = self.start if start is None else start
        states, rewards = [s], []
        while not self.terminal[s]:
            s, r = self.step(s, rng)
            states.append(s); rewards.append(r)
        return states, rewards


@dataclass
class TabularMDP:
    """
    P[a, s, s']  transition probabilities for action a (terminal rows zero)
    R[a, s, s']  reward on the transition
    """
    P: np.ndarray
    R: np.ndarray
    terminal: np.ndarray
    start: int
    gamma: float = 1.0

    def __post_init__(self):
        self.n_actions, self.n_states = self.P.shape[0], self.P.shape[1]

    def under(self, pi):
        """The Markov reward process induced by policy pi[s, a] (rows sum to 1)."""
        P = np.einsum("sa,ast->st", pi, self.P)
        with np.errstate(invalid="ignore", divide="ignore"):
            R = np.where(P > 0, np.einsum("sa,ast,ast->st", pi, self.P, self.R) / P, 0.0)
        return TabularMRP(P, R, self.terminal.copy(), self.start, self.gamma)

    def value_iteration(self, tol=1e-12, max_iter=100000):
        """Optimal V* and a greedy policy (for ground truth on small problems)."""
        V = np.zeros(self.n_states)
        r_sa = (self.P * self.R).sum(axis=2)                  # [a, s]
        for _ in range(max_iter):
            Q = r_sa + self.gamma * self.P @ V                # [a, s]
            V_new = np.where(self.terminal, 0.0, Q.max(axis=0))
            if np.max(np.abs(V_new - V)) < tol:
                V = V_new; break
            V = V_new
        Q = r_sa + self.gamma * self.P @ V
        return V, Q.argmax(axis=0)


def frozen_lake(map_name="4x4", is_slippery=True, gamma=0.99):
    """Exact model of Gymnasium's FrozenLake from env.unwrapped.P (reward 1 on reaching the goal)."""
    import gymnasium as gym
    env = gym.make("FrozenLake-v1", map_name=map_name, is_slippery=is_slippery)
    model = env.unwrapped.P
    nS, nA = env.observation_space.n, env.action_space.n
    P = np.zeros((nA, nS, nS)); R = np.zeros((nA, nS, nS)); terminal = np.zeros(nS, dtype=bool)
    desc = env.unwrapped.desc.flatten()
    for s in range(nS):
        if desc[s] in (b"H", b"G"):
            terminal[s] = True
            continue
        for a in range(nA):
            for p, s2, r, _done in model[s][a]:
                P[a, s, s2] += p
                R[a, s, s2] = r
    env.close()
    return TabularMDP(P, R, terminal, start=0, gamma=gamma)


def random_walk_19():
    """
    Sutton & Barto §7.1: states 1..19 nonterminal, 0 and 20 terminal, start 10. Moves left or right with probability
    1/2. Reward -1 on entering 0, +1 on entering 20, else 0. gamma = 1. True V(s) = (s - 10) / 10 for s = 1..19.
    """
    n = 21
    P = np.zeros((n, n)); R = np.zeros((n, n))
    for s in range(1, 20):
        P[s, s - 1] = 0.5; P[s, s + 1] = 0.5
    R[1, 0] = -1.0; R[19, 20] = 1.0
    terminal = np.zeros(n, dtype=bool); terminal[[0, 20]] = True
    true_v = np.array([0.0] + [(s - 10) / 10 for s in range(1, 20)] + [0.0])
    return TabularMRP(P, R, terminal, start=10, gamma=1.0, true_v=true_v)
