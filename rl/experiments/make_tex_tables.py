"""
Turn experiment JSON results into LaTeX fragments for docs/rl/ParaDEVS_RL_Plan.tex.

For each experiment: a table of the best step size per (method, n) with RMS error, standard error and work per episode,
plus the figure. The interpretation paragraph is written by hand in the plan; this only renders data.

Run from rl/:  .venv/Scripts/python.exe experiments/make_tex_tables.py
"""
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.join(HERE, "..", "..", "docs", "rl")
LABELS = {"A_ntd": "A: n-step TD (model-free)", "B_exact": "B: exact (exhaustive + merge)",
          "C_budget": "C: sampled + merge + budget", "D_rollouts": "D: independent rollouts"}

EXPERIMENTS = [
    ("t1_nstep_td", "sec:t1", "T1: 19-state random walk, n-step TD prediction",
     "RMS error over the 19 states, averaged over the first {episodes} episodes and {runs} runs."),
    ("t2_frozenlake_prediction", "sec:t2", "T2: slippery FrozenLake 8$\\times$8, prediction",
     "RMS error over nonterminal states, averaged over the first {episodes} episodes and {runs} runs "
     "(exploring starts; $\\varepsilon={eps}$-soft optimal policy; $\\gamma=0.99$)."),
]


def render(name, label, title, caption):
    path = os.path.join(HERE, "results", f"{name}.json")
    if not os.path.exists(path):
        return f"% {name}: no results yet\n"
    d = json.load(open(path))
    rows = d["rows"]
    ns = sorted({r["n"] for r in rows})
    lines = [f"\\subsection{{{title}}}", f"\\label{{{label}}}", "\\begin{center}\\small",
             "\\begin{tabular}{@{}l" + "r" * len(ns) + "@{}}", "\\toprule",
             "\\textbf{Target} & " + " & ".join(f"\\textbf{{n={n}}}" for n in ns) + "\\\\", "\\midrule"]
    for m, lab in LABELS.items():
        cells_rms, cells_work = [], []
        for n in ns:
            cand = [r for r in rows if r["method"] == m and r["n"] == n]
            if not cand:
                cells_rms.append("--"); cells_work.append("--"); continue
            b = min(cand, key=lambda r: r["rms"])
            cells_rms.append(f"{b['rms']:.3f}\\,$\\pm$\\,{b['rms_se']:.3f}")
            cells_work.append(f"$\\alpha$={b['alpha']:g}, " + f"{b['work']:,.0f}".replace(",", "{,}"))
        lines.append(f"{lab} & " + " & ".join(cells_rms) + "\\\\")
        lines.append("\\quad best $\\alpha$, work/episode & " + " & ".join(cells_work) + "\\\\[2pt]")
    lines += ["\\bottomrule", "\\end{tabular}", "\\end{center}",
              "\\noindent Best step size per cell. " + caption.format(**{k: d.get(k, "") for k in ("episodes", "runs", "eps")})
              + " Work counts transitions examined or sampled, including the agent's own steps.", ""]
    png = os.path.join(HERE, "results", f"{name}.png")
    if os.path.exists(png):
        shutil.copy(png, os.path.join(DOCS, f"{name}.png"))
        lines += ["\\begin{figure}[h]\\centering", f"\\includegraphics[width=\\linewidth]{{{name}.png}}",
                  f"\\caption{{{title}: error against step size for each target, and best-$\\alpha$ error against work.}}",
                  "\\end{figure}", ""]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    os.makedirs(DOCS, exist_ok=True)
    for name, label, title, caption in EXPERIMENTS:
        out = os.path.join(DOCS, f"{name}_table.tex")
        with open(out, "w") as f:
            f.write(render(name, label, title, caption))
        print("wrote", os.path.relpath(out, os.path.join(HERE, "..", "..")))
