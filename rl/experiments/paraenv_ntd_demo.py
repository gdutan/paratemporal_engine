"""
End-to-end demo of the ParaEnv pipeline (Gymnasium env over the Java engine, learner in Python).

Part 1. Tabular n-step TD prediction on ParaEnv, two problems:
  plain   Sutton & Barto's n-step TD: the n-step return of the agent's own trajectory (model-free)
  exact   at each visited state S_t, V(S_t) += alpha (G - V(S_t)) with G = para_eval(S_t, n, V), exhaustive + safe
          merge (the engine's exact expected n-step target)
  budget  the same with the engine's sampled target, budget B = 4 per draw level, safe merge (unbiased)
The agent moves with env.step() in every case. Error: RMS over nonterminal states against the true V
(random walk: (s - 10) / 10; FrozenLake: V^pi of the eps-soft optimal policy, eps = 0.2, gamma = 0.99), averaged over
the episodes and over a few seeded runs. Work: engine departures plus the agent's own steps. FrozenLake episodes use
exploring starts (reset(options={"state": s})) so that every state gets updates; the random walk starts at 10.

Part 2. Stable-Baselines3 DQN trains on OneHot(gym.make("ParaDEVS/FrozenLake8x8-v0")) for a few thousand steps (the
point is that the standard tooling runs on ParaEnv without errors, not that DQN solves the task in that budget).

Run from rl/:  .venv/Scripts/python.exe experiments/paraenv_ntd_demo.py [--runs 3] [--sb3-steps 3000]
Output: rl/experiments/results/paraenv_ntd_demo.csv
"""
import argparse
import csv
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import gymnasium as gym  # noqa: E402

from paratemporal_py.para_env import OneHot, ParaEnv  # noqa: E402


def act(env, pi, rng):
    """Action from the env's evaluated policy pi (the policy the engine targets are computed under)."""
    if env.nA == 1:
        return 0
    return int(rng.choice(env.nA, p=pi[env.state]))


def start(env, rng, explore):
    """Reset; with exploring starts, begin in a uniformly random nonterminal state (reset option "state")."""
    if not explore:
        return env.reset()[0]
    return env.reset(options={"state": int(rng.choice(np.flatnonzero(~env.terminal)))})[0]


def plain_ntd(env, pi, V, n, alpha, rng, max_steps, explore):
    """One episode of S&B n-step TD on env (Section 7.1). Returns work (agent steps)."""
    s = start(env, rng, explore)
    S, R, T, t = [s], [0.0], np.inf, 0
    g = env.gamma
    while True:
        if t < T:
            s2, r, term, trunc, _ = env.step(act(env, pi, rng))
            S.append(s2); R.append(r)
            if term or t + 1 >= max_steps:
                T = t + 1
        tau = t - n + 1
        if tau >= 0:
            G = sum(g ** (i - tau - 1) * R[i] for i in range(tau + 1, int(min(tau + n, T)) + 1))
            m = int(min(tau + n, T))
            G += g ** (m - tau) * V[S[m]]              # V is 0 at terminal states; bootstraps a truncated tail
            V[S[tau]] += alpha * (G - V[S[tau]])
        if tau == T - 1:
            break
        t += 1
    return int(T)


def engine_td(env, pi, V, n, alpha, rng, max_steps, explore, seed, **rule):
    """One episode: update every visited state toward the engine's n-step target. Returns work."""
    s = start(env, rng, explore); work = 0; steps = 0
    while not env.terminal[s] and steps < max_steps:
        tgt = env.para_eval(s, n, V, seed=seed + steps, **rule)
        V[s] += alpha * (tgt.value - V[s]); work += tgt.work
        s = env.step(act(env, pi, rng))[0]; work += 1; steps += 1
    return work


def part1(runs, out_rows):
    problems = [
        # name, env kwargs, n, alpha, episodes, max steps per episode, exploring starts
        ("random walk 19", dict(problem="randomwalk19"), 4, 0.4, 10, 10_000, False),
        ("FrozenLake 8x8", dict(problem="frozenlake8x8", gamma=0.99, policy="eps_soft_optimal", eps=0.2), 2, 0.2, 30,
         200, True),
    ]
    methods = [("plain", None), ("exact", dict(rule="exhaustive", merge="safe")),
               ("budget B=4", dict(rule="budget", budget=4, merge="safe"))]
    print("Part 1: tabular n-step TD prediction on ParaEnv")
    print(f"  {'problem':16s} {'method':12s} {'n':>2s} {'alpha':>5s} {'RMS (mean over episodes)':>26s} "
          f"{'final RMS':>10s} {'work/episode':>13s} {'sec':>6s}")
    for pname, kw, n, alpha, episodes, max_steps, explore in problems:
        env = ParaEnv(**kw)
        pi = env.policy
        trueV = (np.arange(env.nS) - 10) / 10.0 if kw["problem"] == "randomwalk19" else env.evaluate_policy()
        trueV[env.terminal] = 0
        live = ~env.terminal
        for mname, rule in methods:
            t0 = time.perf_counter()
            errs, finals, works = [], [], []
            for run in range(runs):
                env.reset(seed=1000 + run)
                rng = np.random.default_rng(1000 + run)
                V = np.zeros(env.nS); ep_err = []; w = 0
                for ep in range(episodes):
                    if rule is None:
                        w += plain_ntd(env, pi, V, n, alpha, rng, max_steps, explore)
                    else:
                        w += engine_td(env, pi, V, n, alpha, rng, max_steps, explore, seed=10 ** 6 * run + 10 ** 4 * ep, **rule)
                    ep_err.append(np.sqrt(np.mean((V[live] - trueV[live]) ** 2)))
                errs.append(np.mean(ep_err)); finals.append(ep_err[-1]); works.append(w / episodes)
            sec = time.perf_counter() - t0
            e, se = np.mean(errs), np.std(errs, ddof=1) / np.sqrt(runs) if runs > 1 else 0.0
            print(f"  {pname:16s} {mname:12s} {n:2d} {alpha:5.2f} {e:18.4f} +- {se:.4f} {np.mean(finals):10.4f} "
                  f"{np.mean(works):13.0f} {sec:6.1f}")
            out_rows.append(dict(part="ntd", problem=pname, method=mname, n=n, alpha=alpha, episodes=episodes,
                                 runs=runs, rms_mean=e, rms_se=se, rms_final=np.mean(finals),
                                 work_per_episode=np.mean(works), seconds=sec))
        env.close()


def part2(steps, out_rows):
    import torch
    torch.set_num_threads(2)
    from stable_baselines3 import DQN
    from stable_baselines3.common.env_checker import check_env as sb3_check
    from stable_baselines3.common.evaluation import evaluate_policy

    print(f"Part 2: SB3 DQN on OneHot(ParaDEVS/FrozenLake8x8-v0), {steps} steps")
    from stable_baselines3.common.monitor import Monitor
    env = Monitor(OneHot(gym.make("ParaDEVS/FrozenLake8x8-v0")))
    sb3_check(env, warn=True)
    t0 = time.perf_counter()
    model = DQN("MlpPolicy", env, learning_starts=500, buffer_size=10_000, exploration_fraction=0.5,
                train_freq=4, target_update_interval=500, seed=0, verbose=0, device="cpu")
    model.learn(total_timesteps=steps)
    sec = time.perf_counter() - t0
    mean_r, std_r = evaluate_policy(model, env, n_eval_episodes=50, deterministic=True)
    print(f"  trained {steps} steps in {sec:.1f} s without errors; greedy success rate over 50 episodes: "
          f"{mean_r:.2f} (+- {std_r:.2f})")
    out_rows.append(dict(part="sb3", problem="FrozenLake 8x8 (one-hot)", method="DQN", n="", alpha="", episodes=50,
                         runs=1, rms_mean="", rms_se="", rms_final="", work_per_episode="", seconds=sec,
                         sb3_steps=steps, sb3_success=mean_r))
    env.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--sb3-steps", type=int, default=3000)
    ap.add_argument("--skip-sb3", action="store_true")
    args = ap.parse_args()
    rows = []
    part1(args.runs, rows)
    if not args.skip_sb3:
        part2(args.sb3_steps, rows)
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    path = os.path.join(HERE, "results", "paraenv_ntd_demo.csv")
    keys = sorted({k for r in rows for k in r}, key=lambda k: list(rows[0]).index(k) if k in rows[0] else 99)
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader(); w.writerows(rows)
    print("wrote", path)


if __name__ == "__main__":
    main()
