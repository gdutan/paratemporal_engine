"""Figure for the Java engine n-step comparison (examples.rl.CompareNStep).

Reads rl/experiments/results/java_nstep_compare.csv and writes java_nstep_compare.png there:
efficiency (variance x work, log scale) and work against lookahead n, for k = 3.
Run from the repository root:  rl/.venv/Scripts/python.exe rl/experiments/plot_java_nstep.py
"""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

HERE = Path(__file__).resolve().parent
CSV = HERE / "results" / "java_nstep_compare.csv"
OUT = HERE / "results" / "java_nstep_compare.png"

SAMPLED = [("k-tree, no merge", "#9aa5b1", "o", "--"),
           ("k-tree + merge", "#3b6ea8", "o", "-"),
           ("budget B=k^2 + merge", "#c2571a", "s", "-"),
           ("rollouts N=k^2", "#555555", "^", ":")]
EXACT = [("exhaustive, no merge", "#9aa5b1", "D", "--"),
         ("exhaustive + merge", "#2e7d32", "D", "-")]


def main():
    df = pd.read_csv(CSV)
    problems = list(dict.fromkeys(df["problem"]))
    fig, axes = plt.subplots(2, len(problems), figsize=(10, 6.4), constrained_layout=True)
    for j, prob in enumerate(problems):
        d = df[df["problem"] == prob]
        ax = axes[0, j]
        for name, col, mk, ls in SAMPLED:
            r = d[(d["method"] == name) & (d["k"] == 3)].sort_values("n")
            ax.plot(r["n"], r["efficiency_var_x_work"], color=col, marker=mk, ls=ls, label=name.replace("k^2", "9"))
        ax.set_yscale("log")
        ax.set_title(f"{prob}: efficiency, k = 3 (lower is better)", fontsize=10)
        ax.set_xlabel("lookahead n")
        ax.set_ylabel("variance x work")
        ax.set_xticks(sorted(d["n"].unique()))
        ax = axes[1, j]
        for name, col, mk, ls in SAMPLED + EXACT:
            sel = d["method"] == name
            r = d[sel & ((d["k"] == 3) | (d["k"] == 0))].sort_values("n")
            ax.plot(r["n"], r["mean_work"], color=col, marker=mk, ls=ls, label=name.replace("k^2", "9"))
        ax.set_yscale("log")
        ax.set_title(f"{prob}: work per target, k = 3", fontsize=10)
        ax.set_xlabel("lookahead n")
        ax.set_ylabel("departures (log)")
        ax.set_xticks(sorted(d["n"].unique()))
    for ax in axes.flat:
        ax.grid(True, which="major", alpha=0.3)
        ax.spines[["top", "right"]].set_visible(False)
    axes[0, 0].legend(fontsize=8, frameon=False)
    axes[1, 0].legend(fontsize=8, frameon=False)
    fig.savefig(OUT, dpi=160)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
