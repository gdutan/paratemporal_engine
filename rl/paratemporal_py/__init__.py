"""
paratemporal_py: Python reference prototypes for the RL track (docs/rl/ParaDEVS_RL_Plan, phase R1).

These mirror what the Java engine will do, on small tabular problems where the transition model is known:

- tabular Markov reward processes (mdp.py),
- n-step target estimators: exact (exhaustive expansion + merging), sampled with merging and per-stage budget
  reallocation, independent Monte Carlo rollouts, and the classic single-trajectory n-step return (targets.py).

Merging here is the expectation-preserving ("accumulator") merge: nodes in the same state are combined and the
probability-weighted accumulated reward is kept as one sum. It is exact for expected returns only. Under that
merge, the set of merged nodes at a stage is exactly a weight vector over states, which is why the estimators are
written with numpy vectors.
"""
from .mdp import TabularMRP, TabularMDP, random_walk_19, frozen_lake
from . import targets

__all__ = ["TabularMRP", "TabularMDP", "random_walk_19", "frozen_lake", "targets"]
