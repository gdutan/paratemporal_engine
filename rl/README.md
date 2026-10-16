# rl/ — the RL track

Python side of ParaDEVS: reference prototypes (`paratemporal_py/`), experiments, baselines and tests. Run everything
from this folder with the project venv (`.venv/Scripts/python.exe`).

## ParaEnv: a Gymnasium environment over the Java engine

`paratemporal_py/para_env.py` exposes the ParaDEVS engine through JPype (the JVM runs in-process). It provides a
standard Gymnasium environment for tabular problems and engine-computed n-step targets for the learner.

**Build the Java side first** (from the repository root, Git Bash): `bash scripts/build_library.sh`. ParaEnv
starts the JVM on first use with `-Xmx512m` and the classpath `build/library/classes` + `lib/ms4-core.jar`
(override with `start_jvm(classpath=..., max_heap=...)` before creating an env, or set `PARADEVS_HOME`).
The JVM can be started only once per process.

```python
import gymnasium as gym
from paratemporal_py.para_env import ParaEnv, OneHot

env = ParaEnv("frozenlake8x8", gamma=0.99)          # or ParaEnv("randomwalk19")
s, info = env.reset(seed=0)                          # reset(options={"state": s}) gives exploring starts
s, r, terminated, truncated, info = env.step(2)      # actions 0..3 = Left, Down, Right, Up

env = gym.make("ParaDEVS/FrozenLake8x8-v0")          # with the 200-step TimeLimit of FrozenLake8x8-v1
env = OneHot(gym.make("ParaDEVS/FrozenLake8x8-v0"))  # Box observations for SB3 (DQN, PPO, ...)
```

Semantics are Gymnasium's FrozenLake: `Discrete(64)` observations, `Discrete(4)` actions, slippery moves (the
intended direction or one of the two perpendicular ones, 1/3 each), reward 1.0 on arriving at the goal, `terminated`
on arriving at a hole or the goal, `truncated` only from the `TimeLimit` wrapper. The random walk is Sutton & Barto's
19-state walk (`Discrete(21)`, one action, reward -1/+1 at the left/right end, start 10). The step RNG lives in
Java (SplitMix64, seeded from `np_random`), so `reset(seed=...)` is reproducible; `snapshot()` / `restore()` save
and resume the exact state including the RNG.

### Engine targets: `para_eval`

```python
env.set_eps_soft_optimal(0.2)        # the policy targets are computed under (or env.set_policy(pi[s, a]))
V = env.evaluate_policy()            # V^pi from Java (or your current estimate)

t = env.para_eval(18, 4, V)                                     # exhaustive + safe merge: exact
t = env.para_eval(18, 4, V, rule="budget", budget=20, seed=1)   # sampled, budget B per draw level
t = env.para_eval(18, 4, V, rule="mc", k=3, seed=1)             # k i.i.d. branches per draw
t = env.para_eval(18, 4, V, rule="rollouts", roots=20, merge="none", seed=1)
t.value, t.work                       # estimate; work = engine departures (incl. replays)

values, work = env.para_eval([0, 18, 27], 4, V)                 # batched: one Java call, item i seeded seed + i
values, work = env.para_eval_replications(18, 4, V, reps=400, rule="budget", budget=20)
```

The target is `E[R_1 + ... + gamma^(n-1) R_n + gamma^n V(S_n) | S_0 = s]` under the env's policy, with V = 0 at
terminal states. It is computed by `examples.rl.NStepTargets.target` through the bridge class
`examples.rl.env.MdpSession`. `merge` is `"safe"` (default) or `"none"`; `mode` is `"joint"` (one draw per step)
or `"separate"` (an action draw, then a transition draw).

### Planner extension point: `plan`

```python
res = env.plan(n=3, V=V)              # at the current state; or plan(..., state=s)
res.values                            # Q_n(s, a) for every action
res.work
```

`env.planner` is any object with `action_values(env, state, n, V, *, rule, merge, k, budget, roots, seed) ->
PlanResult` (the `Planner` protocol), or a Java `examples.rl.env.ActionPlanner` (wrapped automatically). The default
is the Java `PolicyLookaheadPlanner`: one engine run per action, with the first action forced and the env's policy
after it, so `sum_a pi(a|s) Q_n(s,a)` equals the n-step target. A graph-mode planner on `Para.decide` (maximising
over actions at each decision) can implement `ActionPlanner` and be installed with
`env.planner = JClass("...")(...)`, with no change to ParaEnv or to the learner.

### Cost of the bridge

Measured by `measure_overhead(env)` (also printed by the tests). On this machine: a Java call costs about 2 µs, and
`env.step` about 3 to 5 µs; `step_batch` brings this to about 0.1 µs per step. An n = 1 exhaustive target costs
about 1 ms, nearly all of it inside the engine run; batching saves about 0.15 ms per target. Use the batch methods
(`para_eval` with a list of states, `para_eval_replications`, `step_batch`, `sample_transitions`) for many targets.

### Tests and demo

- `tests/test_para_env.py`: `check_env`; the FrozenLake model and transition statistics against Gymnasium's
  `FrozenLake-v1` (8x8, slippery); exhaustive `para_eval` against `targets.exact` (1e-12, V^pi and a random V);
  unbiasedness of the sampled rules; the planner identity; the JPype overhead.
- `experiments/paraenv_ntd_demo.py`: tabular n-step TD with engine targets versus plain n-step TD on both problems,
  and SB3 DQN training on `OneHot(ParaEnv)` (output `experiments/results/paraenv_ntd_demo.csv`).

### Future: OpenEnv / TRL adapter (not built)

TRL's `environment_factory` creates one environment instance per generation, and OpenEnv and Prime Intellect's
`verifiers` are becoming the standard environment interfaces (MeetingNotes, 2026-10-15, Section 3). The intended path:

1. Keep `ParaEnv` (and the `MdpSession` bridge) as the single source of truth for dynamics, seeding and targets.
2. Write a thin OpenEnv server/client pair (or a `verifiers` environment) whose `reset`/`step` call ParaEnv, with
   text observations and actions for LLM agents.
3. Put "play each action forward k times" inside that wrapper: at a decision, call `env.plan(...)` or `para_eval`
   (k branches or a budget) and return the averaged target as the reward signal, so TRL sees one ordinary
   environment per generation.
4. Transport stays independent of the API: JPype in-process first; a socket or service transport only if the trainer
   and the engine must run in different processes.
