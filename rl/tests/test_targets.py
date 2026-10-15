"""
Checks for paratemporal_py.targets on the 19-state random walk.

Run from rl/:  .venv/Scripts/python.exe tests/test_targets.py
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from paratemporal_py import random_walk_19, targets  # noqa: E402

failures = 0


def check(name, ok, detail=""):
    global failures
    print(("  PASS " if ok else "  FAIL ") + name + ("" if ok else "  -> " + detail))
    failures += 0 if ok else 1


mrp = random_walk_19()
V = mrp.true_v

print("== exact targets")
# Bellman consistency: with the true V, every n-step target equals V(s).
worst = max(abs(targets.exact(mrp, s, n, V)[0] - V[s]) for s in range(1, 20) for n in (1, 2, 3, 5, 8, 16, 40))
check("Bellman consistency: exact target with true V equals V(s) for all s, n", worst < 1e-12, f"max err {worst}")

# Against matrix powers with an arbitrary V.
rng = np.random.default_rng(0)
Vr = rng.normal(size=mrp.n_states); Vr[mrp.terminal] = 0
s, n = 13, 6
Pm, w, acc = mrp.P.copy(), np.eye(mrp.n_states)[s], 0.0
for i in range(n):
    acc += w @ mrp.r_expected; w = w @ Pm
want = acc + w @ Vr
got, work = targets.exact(mrp, s, n, Vr)
check("exact target matches matrix-power computation", abs(got - want) < 1e-12, f"{got} vs {want}")
check("exact work is small (merging)", work <= 2 * n * 19, f"work {work}")

print("== sampled estimators are unbiased (arbitrary V)")
reps = 4000
for name, fn in [("budget B=10", lambda r: targets.budget(mrp, s, n, Vr, 10, r)),
                 ("rollouts N=10", lambda r: targets.rollouts(mrp, s, n, Vr, 10, r))]:
    vals = np.array([fn(rng)[0] for _ in range(reps)])
    se = vals.std(ddof=1) / np.sqrt(reps)
    check(f"{name}: mean within 4 s.e. of exact", abs(vals.mean() - want) < 4 * se,
          f"mean {vals.mean():.5f}, exact {want:.5f}, se {se:.5f}")

print(f"== {failures} failure(s)")
sys.exit(1 if failures else 0)
