"""
T2 (prediction): slippery FrozenLake 8x8, gamma = 0.99, fixed stochastic policy = epsilon-soft version (eps = 0.2) of
the optimal greedy policy. True V^pi is computed exactly (linear solve) for scoring.

Same four update targets as T1 (A n-step TD, B exact, C sampled+merge+budget, D rollouts). Episodes start in a
uniformly random nonterminal state (exploring starts) so every state is learned. Error = RMS over nonterminal states,
averaged over the first EPISODES episodes and over RUNS runs. Slippery moves make many paths reach the same square,
so merging collapses the tree much more than on the random walk.

Results are saved after every (method, n) block and a rerun resumes (see _resume.py).

Run from rl/:  .venv/Scripts/python.exe experiments/t2_frozenlake_prediction.py [--methods ...] [--runs 50]
Outputs (rl/experiments/results/): t2_frozenlake_prediction.json (incremental), .csv and .png when complete
"""
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
from paratemporal_py import frozen_lake, learn  # noqa: E402
import _resume  # noqa: E402
from t1_nstep_td import plot, NS  # noqa: E402

EPISODES, EPS, BUDGET = 30, 0.2, 4
ALPHAS = [0.05, 0.1, 0.2, 0.4, 0.6, 0.8, 1.0]
NAME = "t2_frozenlake_prediction"
OUT = os.path.join(HERE, "results")


def main():
    a = _resume.args(default_runs=50)
    os.makedirs(OUT, exist_ok=True)
    mdp = frozen_lake("8x8", gamma=0.99)
    _, greedy = mdp.value_iteration()
    pi = np.full((mdp.n_states, mdp.n_actions), EPS / mdp.n_actions)
    pi[np.arange(mdp.n_states), greedy] += 1 - EPS
    mrp = mdp.under(pi)
    live = ~mrp.terminal
    A = np.eye(mrp.n_states)[np.ix_(live, live)] - mrp.gamma * mrp.P[np.ix_(live, live)]
    v_true = np.zeros(mrp.n_states); v_true[live] = np.linalg.solve(A, mrp.r_expected[live])
    nonterm = np.flatnonzero(live)
    start_fn = lambda rng: int(rng.choice(nonterm))
    err_fn = lambda V: float(np.sqrt(np.mean((V[live] - v_true[live]) ** 2)))
    print(f"V^pi(start) = {v_true[0]:.4f}; {live.sum()} nonterminal states", flush=True)

    methods = {
        "A_ntd": lambda n, al, r: learn.ntd(mrp, n, al, r, EPISODES, start_fn, err_fn),
        "B_exact": lambda n, al, r: learn.model_target(mrp, n, al, r, EPISODES, start_fn, err_fn, "exact"),
        "C_budget": lambda n, al, r: learn.model_target(mrp, n, al, r, EPISODES, start_fn, err_fn, "budget", BUDGET),
        "D_rollouts": lambda n, al, r: learn.model_target(mrp, n, al, r, EPISODES, start_fn, err_fn, "rollouts", BUDGET),
    }
    meta = dict(runs=a.runs, episodes=EPISODES, eps=EPS, budget=BUDGET, v_start=round(float(v_true[0]), 10))
    path = os.path.join(OUT, NAME + ".json")
    rows = _resume.load(path, meta)
    t0 = time.time()
    for mname in (a.methods or methods) if not a.plot_only else []:
        for n in NS:
            if _resume.done(rows, mname, n):
                continue
            for alpha in ALPHAS:
                errs, works = [], []
                for run in range(a.runs):
                    e, w = methods[mname](n, alpha, np.random.default_rng(10_000 * run + 29))
                    errs.append(e); works.append(w)
                rows.append(dict(method=mname, n=n, alpha=alpha, rms=float(np.mean(errs)),
                                 rms_se=float(np.std(errs, ddof=1) / np.sqrt(a.runs)), work=float(np.mean(works))))
            _resume.save(path, meta, rows)
            best = min((r for r in rows if r["method"] == mname and r["n"] == n), key=lambda r: r["rms"])
            print(f"{mname:11s} n={n}: best alpha={best['alpha']:.2f} rms={best['rms']:.4f} "
                  f"work/episode={best['work']:.0f}   [{time.time() - t0:.0f}s]", flush=True)

    if not all(_resume.done(rows, m, n) for m in methods for n in NS):
        left = [(m, n) for m in methods for n in NS if not _resume.done(rows, m, n)]
        print(f"incomplete: {len(left)} block(s) left, e.g. {left[:3]}; rerun to resume")
        return
    with open(os.path.join(OUT, NAME + ".csv"), "w") as f:
        f.write("method,n,alpha,rms,rms_se,work_per_episode\n")
        for r in rows:
            f.write(f"{r['method']},{r['n']},{r['alpha']},{r['rms']:.6f},{r['rms_se']:.6f},{r['work']:.1f}\n")
    top = max(r["rms"] for r in rows) * 1.05
    plot(rows, list(methods), os.path.join(OUT, NAME + ".png"), f"RMS error, first {EPISODES} episodes", top)
    print(f"wrote results/{NAME}.{{csv,json,png}}")


if __name__ == "__main__":
    main()
