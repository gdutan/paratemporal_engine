"""
Checks for ParaEnv (Gymnasium environment over the Java engine) and the engine targets para_eval / plan.

Build the Java side first (bash scripts/build_library.sh), then run from rl/:
    .venv/Scripts/python.exe tests/test_para_env.py
"""
import os
import sys
import time
import warnings

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import gymnasium as gym  # noqa: E402
from gymnasium.utils.env_checker import check_env  # noqa: E402

from paratemporal_py import frozen_lake, random_walk_19, targets  # noqa: E402
from paratemporal_py.para_env import ParaEnv, Snapshot, measure_overhead  # noqa: E402

failures = 0


def check(name, ok, detail=""):
    global failures
    print(("  PASS " if ok else "  FAIL ") + name + ("" if ok else "  -> " + detail))
    failures += 0 if ok else 1


t_start = time.perf_counter()
fl = ParaEnv("frozenlake8x8", gamma=0.99)
rw = ParaEnv("randomwalk19")

# ------------------------------------------------------------------------------------------------ Gymnasium API
print("== Gymnasium API")
for name, env in (("FrozenLake 8x8", fl), ("random walk", rw)):
    try:
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            check_env(env, skip_render_check=True)
        msgs = sorted({str(w.message)[:90] for w in caught})
        check(f"check_env passes ({name})", True)
        if msgs:
            print("    warnings:", msgs)
    except Exception as e:  # noqa: BLE001
        check(f"check_env passes ({name})", False, repr(e))

made = gym.make("ParaDEVS/FrozenLake8x8-v0")
check("gym.make('ParaDEVS/FrozenLake8x8-v0') has the FrozenLake8x8-v1 time limit (200)",
      made.spec.max_episode_steps == gym.spec("FrozenLake8x8-v1").max_episode_steps == 200)
made.close()

# Seeding and snapshots.
a = [fl.reset(seed=123)[0]] + [fl.step(i % 4)[0] for i in range(40)]
b = [fl.reset(seed=123)[0]] + [fl.step(i % 4)[0] for i in range(40)]
check("reset(seed) makes trajectories reproducible", a == b)
fl.reset(seed=5)
for i in range(3):
    fl.step(1)
snap = fl.snapshot()
x = [fl.step(2)[0] for _ in range(10)]
fl.restore(snap)
y = [fl.step(2)[0] for _ in range(10)]
check("snapshot/restore resumes exactly (state, steps, RNG)", x == y and isinstance(snap, Snapshot), f"{x} vs {y}")

# ------------------------------------------------------------------------------------------------ FrozenLake semantics
print("== FrozenLake 8x8 matches Gymnasium's FrozenLake-v1 (slippery)")
genv = gym.make("FrozenLake-v1", map_name="8x8", is_slippery=True)
gu = genv.unwrapped
nS, nA = fl.nS, fl.nA
check("same spaces", genv.observation_space == fl.observation_space and genv.action_space == fl.action_space)

# Model: same P(s'|s,a), and the same reward and termination for every possible transition.
Pg = np.zeros((nA, nS, nS)); model_ok = True
for s in range(nS):
    for act in range(nA):
        for p, s2, r, term in gu.P[s][act]:
            if fl.terminal[s]:
                continue
            Pg[act, s, s2] += p
            model_ok &= (float(r) == float(fl.reward_on_arrival[s2])) and (bool(term) == bool(fl.terminal[s2]))
check("rewards and terminated flags agree on every transition", model_ok)
live = ~fl.terminal
check("transition probabilities agree (to 1e-15; slip mass summed in a different order)",
      np.allclose(Pg[:, live], fl.P[:, live], rtol=0, atol=1e-15),
      f"max diff {np.abs(Pg[:, live] - fl.P[:, live]).max()}")

# Empirical transition statistics: ParaEnv's sampler and Gymnasium's step, against the exact P.
N = 2000
gu.reset(seed=0)
fl.reset(seed=0)
chi_para = chi_gym = 0.0; df = 0; worst_z = 0.0; worst_diff = 0.0
for s in np.flatnonzero(live):
    for act in range(nA):
        p = fl.P[act, s]
        sup = np.flatnonzero(p > 0)
        cp = np.bincount(fl.sample_transitions(int(s), act, N), minlength=nS)[sup]
        # Step the unwrapped Gymnasium env directly from s (no time limit); count outcomes.
        cg = np.zeros(nS)
        for _ in range(N):
            gu.s = int(s)
            cg[gu.step(act)[0]] += 1
        cg = cg[sup]
        e = N * p[sup]
        chi_para += float(((cp - e) ** 2 / e).sum()); chi_gym += float(((cg - e) ** 2 / e).sum()); df += sup.size - 1
        se = np.sqrt(p[sup] * (1 - p[sup]) / N)
        worst_z = max(worst_z, float(np.max(np.abs(cp / N - p[sup]) / np.where(se > 0, se, 1))))
        worst_diff = max(worst_diff, float(np.max(np.abs(cp - cg)) / N))
lim = df + 5 * np.sqrt(2 * df)
print(f"    {N} draws per (s,a): chi2 ParaEnv {chi_para:.1f}, Gymnasium {chi_gym:.1f} (df {df}, limit {lim:.1f}); "
      f"max |z| ParaEnv {worst_z:.2f}; max freq diff ParaEnv vs Gymnasium {worst_diff:.3f}")
check("ParaEnv transition frequencies fit P (chi-square)", chi_para < lim, f"{chi_para:.1f} >= {lim:.1f}")
check("Gymnasium transition frequencies fit the same P (chi-square)", chi_gym < lim, f"{chi_gym:.1f} >= {lim:.1f}")
check("every ParaEnv cell within 5 s.e. of P", worst_z < 5, f"max z {worst_z:.2f}")

# Episode statistics under the uniform random policy, with the FrozenLake8x8-v1 time limit (200).
def episodes(env, n_ep, seed):
    rng = np.random.default_rng(seed)
    env.reset(seed=seed)
    succ, lens, trunc = [], [], 0
    for _ in range(n_ep):
        env.reset(); total = 0.0; t = 0
        while True:
            _, r, term, tr, _ = env.step(int(rng.integers(4)))
            total += r; t += 1
            if term or tr:
                trunc += int(tr and not term)
                break
        succ.append(total); lens.append(t)
    return np.array(succ), np.array(lens), trunc


E = 3000
g8 = gym.make("FrozenLake8x8-v1")
p8 = gym.make("ParaDEVS/FrozenLake8x8-v0")
sg, lg, tg = episodes(g8, E, 1)
sp, lp, tp = episodes(p8, E, 2)
se_len = np.sqrt(lg.var(ddof=1) / E + lp.var(ddof=1) / E)
se_succ = np.sqrt(sg.var(ddof=1) / E + sp.var(ddof=1) / E) + 1e-12
print(f"    random policy, {E} episodes: length {lp.mean():.2f} vs {lg.mean():.2f} (s.e. {se_len:.2f}); "
      f"success {sp.mean():.4f} vs {sg.mean():.4f}; truncated {tp} vs {tg}")
check("episode length matches Gymnasium within 4 s.e.", abs(lp.mean() - lg.mean()) < 4 * se_len)
check("success rate matches Gymnasium within 4 s.e.", abs(sp.mean() - sg.mean()) < 4 * se_succ)
check("rewards are 0.0/1.0 floats", set(np.unique(sp)) <= {0.0, 1.0})
g8.close(); p8.close(); genv.close()

# Stepping from a terminal state (Gymnasium: stays, reward 0, terminated).
fl.reset(seed=0, options={"state": 19})        # 19 is a hole
s2, r, term, tr, _ = fl.step(0)
check("step from a terminal state stays put with reward 0, terminated", (s2, r, term, tr) == (19, 0.0, True, False))

# ------------------------------------------------------------------------------------------------ para_eval exact
print("== para_eval exhaustive equals targets.exact (1e-12)")
mdp = frozen_lake("8x8", gamma=0.99)
Vstar, _ = mdp.value_iteration()
r_sa = (mdp.P * mdp.R).sum(axis=2)
Q = r_sa + mdp.gamma * mdp.P @ np.where(mdp.terminal, 0.0, Vstar)
greedy = Q.argmax(axis=0)                                   # ties to the lowest action
eps = 0.2
pi = np.full((nS, nA), eps / nA); pi[np.arange(nS), greedy] += 1 - eps
fl.set_eps_soft_optimal(eps)
check("Java eps-soft policy (eps 0.2, ties lowest) equals the Python construction",
      np.allclose(fl.policy, pi, atol=1e-15), f"max diff {np.abs(fl.policy - pi).max()}")
mrp = mdp.under(pi)
A = np.eye(nS) - mrp.gamma * mrp.P[:, ~mrp.terminal] @ np.eye(nS)[~mrp.terminal]
Vpi = np.linalg.solve(A, mrp.r_expected); Vpi[mrp.terminal] = 0
check("Java V^pi equals the Python linear solve", np.allclose(fl.evaluate_policy(), Vpi, atol=1e-10),
      f"{np.abs(fl.evaluate_policy() - Vpi).max()}")
rng = np.random.default_rng(7)
Vr = rng.uniform(-1, 1, nS); Vr[mrp.terminal] = 0
worst = 0.0
for Vname, V in (("V^pi", Vpi), ("random V", Vr)):
    for s in (0, 18, 27, 45, 62):
        for n in (1, 2, 3, 5, 8):
            want = targets.exact(mrp, s, n, V)[0]
            got = fl.para_eval(s, n, V, rule="exhaustive", merge="safe").value
            worst = max(worst, abs(got - want))
            if n <= 3:
                worst = max(worst, abs(fl.para_eval(s, n, V, rule="exhaustive", merge="none").value - want))
check("FrozenLake: V^pi and random V, states {0,18,27,45,62}, n {1,2,3,5,8}, merge safe/none", worst < 1e-12,
      f"max err {worst:.3e}")
vals, work = fl.para_eval([0, 18, 27, 45, 62], 5, Vr)
want = np.array([targets.exact(mrp, s, 5, Vr)[0] for s in (0, 18, 27, 45, 62)])
check("batched para_eval equals targets.exact", np.max(np.abs(vals - want)) < 1e-12)

rwm = random_walk_19()
Vr2 = rng.normal(size=rwm.n_states); Vr2[rwm.terminal] = 0
worst = 0.0
for V in (rwm.true_v, Vr2):
    for s in (1, 5, 10, 13, 19):
        for n in (1, 2, 4, 8, 16):
            worst = max(worst, abs(rw.para_eval(s, n, V).value - targets.exact(rwm, s, n, V)[0]))
check("random walk: true V and random V, 5 states, n up to 16", worst < 1e-12, f"max err {worst:.3e}")

# ------------------------------------------------------------------------------------------------ planner
print("== planner extension point (default PolicyLookaheadPlanner)")
worst_id = worst_q = 0.0
for s in (0, 18, 45):
    for n in (1, 3):
        res = fl.plan(n, Vr, state=s)
        tgt = fl.para_eval(s, n, Vr).value
        worst_id = max(worst_id, abs(float(pi[s] @ res.values) - tgt))
        cont = np.array([0.0 if mrp.terminal[s2] else (Vr[s2] if n == 1 else targets.exact(mrp, s2, n - 1, Vr)[0])
                         for s2 in range(nS)])
        q_py = (mdp.P[:, s] * (fl.reward_on_arrival + mdp.gamma * cont)).sum(axis=1)
        worst_q = max(worst_q, float(np.max(np.abs(res.values - q_py))))
check("sum_a pi(a|s) Q_n(s,a) equals the n-step target", worst_id < 1e-12, f"{worst_id:.3e}")
check("Q_n(s,a) equals the Python one-step-then-exact computation", worst_q < 1e-12, f"{worst_q:.3e}")
check("plan() leaves the live state alone", fl.state == 19)

# ------------------------------------------------------------------------------------------------ sampled
print("== para_eval sampled methods are unbiased (within 4 s.e., seeded)")
reps = 400
for s, n, V, Vname in ((18, 4, Vpi, "V^pi"), (27, 5, Vr, "random V")):
    exact_v = targets.exact(mrp, s, n, V)[0]
    for label, kw in (("k=3 tree, safe merge", dict(rule="mc", k=3, merge="safe")),
                      ("k=3 tree, no merge", dict(rule="mc", k=3, merge="none")),
                      ("budget B=20, safe merge", dict(rule="budget", budget=20, merge="safe")),
                      ("20 rollouts", dict(rule="rollouts", roots=20, merge="none"))):
        v, w = fl.para_eval_replications(s, n, V, reps, seed=1000, **kw)
        se = v.std(ddof=1) / np.sqrt(reps)
        ok = abs(v.mean() - exact_v) <= 4 * se + 1e-12
        print(f"    s={s} n={n} {Vname:8s} {label:24s} mean {v.mean():+.5f} exact {exact_v:+.5f} se {se:.5f} "
              f"work {w.mean():.0f}")
        check(f"{label}, s={s}, n={n}, {Vname}", ok)

# ------------------------------------------------------------------------------------------------ overhead
print("== JPype call overhead")
measure_overhead(fl, 2000)                                   # warm-up (JIT)
ov = measure_overhead(fl, 20000)
for key, val in ov.items():
    print(f"    {key:24s} {val:10.2f} us")
check("overhead measured", all(np.isfinite(list(ov.values()))))

fl.close(); rw.close()
print(f"== {failures} failure(s)  ({time.perf_counter() - t_start:.1f} s)")
sys.exit(1 if failures else 0)
