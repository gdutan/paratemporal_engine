"""
T1: n-step TD prediction on the 19-state random walk (Sutton & Barto §7.1, Figure 7.2 setup).

Measure: RMS error of V against the true values over the 19 states, averaged over the first 10 episodes and over
RUNS independent runs, for a grid of step sizes alpha and n in {1, 2, 4, 8}. V starts at 0.

Update targets compared:
  A  ntd       classic n-step TD: the n-step return of the agent's own trajectory (model-free)
  B  exact     exact expected n-step target from the visited state, by exhaustive expansion with merging
  C  budget    sampled expansion with merging, per-stage budget B (unbiased)
  D  rollouts  N = B independent n-step rollouts from the visited state (unbiased)
B, C and D update V(S_t) at every state the agent visits, using a simulator of the environment (the paratemporal
setting); A uses only the experienced trajectory. Work = transitions examined or sampled, including the agent's
own steps, so the cost of using the model is visible.

Results are saved after every (method, n) block and a rerun resumes (see _resume.py).

Run from rl/:  .venv/Scripts/python.exe experiments/t1_nstep_td.py [--methods A_ntd B_exact ...] [--runs 100]
Outputs (rl/experiments/results/): t1_nstep_td.json (incremental), t1_nstep_td.csv and .png when complete
"""
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
from paratemporal_py import random_walk_19, learn  # noqa: E402
import _resume  # noqa: E402

EPISODES = 10
NS = [1, 2, 4, 8]
ALPHAS = [0.05, 0.1, 0.2, 0.3, 0.4, 0.6, 0.8, 1.0]
BUDGET = 4
NAME = "t1_nstep_td"
OUT = os.path.join(HERE, "results")
TITLES = {"A_ntd": "A: n-step TD (model-free)", "B_exact": "B: exact target (exhaustive + merge)",
          "C_budget": f"C: sampled + merge, budget B={BUDGET}", "D_rollouts": f"D: {BUDGET} rollouts"}


def main():
    a = _resume.args(default_runs=100)
    os.makedirs(OUT, exist_ok=True)
    mrp = random_walk_19()
    live = ~mrp.terminal
    start_fn = lambda rng: mrp.start
    err_fn = lambda V: float(np.sqrt(np.mean((V[live] - mrp.true_v[live]) ** 2)))
    methods = {
        "A_ntd": lambda n, al, r: learn.ntd(mrp, n, al, r, EPISODES, start_fn, err_fn),
        "B_exact": lambda n, al, r: learn.model_target(mrp, n, al, r, EPISODES, start_fn, err_fn, "exact"),
        "C_budget": lambda n, al, r: learn.model_target(mrp, n, al, r, EPISODES, start_fn, err_fn, "budget", BUDGET),
        "D_rollouts": lambda n, al, r: learn.model_target(mrp, n, al, r, EPISODES, start_fn, err_fn, "rollouts", BUDGET),
    }
    meta = dict(runs=a.runs, episodes=EPISODES, budget=BUDGET)
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
                    e, w = methods[mname](n, alpha, np.random.default_rng(10_000 * run + 17))
                    errs.append(e); works.append(w)
                rows.append(dict(method=mname, n=n, alpha=alpha, rms=float(np.mean(errs)),
                                 rms_se=float(np.std(errs, ddof=1) / np.sqrt(a.runs)), work=float(np.mean(works))))
            _resume.save(path, meta, rows)
            best = min((r for r in rows if r["method"] == mname and r["n"] == n), key=lambda r: r["rms"])
            print(f"{mname:11s} n={n}: best alpha={best['alpha']:.2f} rms={best['rms']:.4f} "
                  f"work/episode={best['work']:.0f}   [{time.time() - t0:.0f}s]", flush=True)
    finish(rows, list(methods))


def finish(rows, method_names):
    """Write CSV and figure once every (method, n) block exists."""
    if not all(_resume.done(rows, m, n) for m in method_names for n in NS):
        missing = [(m, n) for m in method_names for n in NS if not _resume.done(rows, m, n)]
        print(f"incomplete: {len(missing)} block(s) left, e.g. {missing[:3]}; rerun to resume")
        return
    with open(os.path.join(OUT, NAME + ".csv"), "w") as f:
        f.write("method,n,alpha,rms,rms_se,work_per_episode\n")
        for r in rows:
            f.write(f"{r['method']},{r['n']},{r['alpha']},{r['rms']:.6f},{r['rms_se']:.6f},{r['work']:.1f}\n")
    plot(rows, method_names, os.path.join(OUT, NAME + ".png"), f"RMS error, first {EPISODES} episodes", 0.55)
    print(f"wrote results/{NAME}.{{csv,json,png}}")


def plot(rows, method_names, out, ylabel, ytop):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 5, figsize=(22, 4.2))
    for ax, mname in zip(axes, method_names):
        for n in NS:
            pts = sorted((r["alpha"], r["rms"]) for r in rows if r["method"] == mname and r["n"] == n)
            ax.plot(*zip(*pts), marker="o", ms=3, label=f"n={n}")
        ax.set_title(TITLES[mname], fontsize=10); ax.set_xlabel("alpha"); ax.set_ylim(0, ytop); ax.grid(alpha=.3)
    axes[0].set_ylabel(ylabel); axes[0].legend(fontsize=8)
    ax = axes[4]
    for mname in method_names:
        pts = [(b["work"], b["rms"]) for b in (min((r for r in rows if r["method"] == mname and r["n"] == n),
                                                  key=lambda r: r["rms"]) for n in NS)]
        ax.plot(*zip(*pts), marker="o", label=mname)
    ax.set_xscale("log"); ax.set_xlabel("work per episode (log)"); ax.set_title("best alpha: error vs work", fontsize=10)
    ax.grid(alpha=.3); ax.legend(fontsize=8)
    fig.tight_layout(); fig.savefig(out, dpi=130); plt.close(fig)


if __name__ == "__main__":
    main()
