"""
ParaEnv: a Gymnasium environment backed by the ParaDEVS Java engine (through JPype), plus engine-computed
n-step targets (``para_eval``) and an extension point for engine planners (``plan``).

Quick start (from rl/)::

    from paratemporal_py.para_env import ParaEnv
    env = ParaEnv("frozenlake8x8")                 # starts the JVM on first use
    s, info = env.reset(seed=0)
    s, r, terminated, truncated, info = env.step(2)

    env.set_eps_soft_optimal(0.2)                  # the policy that targets are computed under
    V = env.evaluate_policy()                      # V^pi (exact, from Java)
    t = env.para_eval(s, 4, V, rule="budget", budget=20, seed=1)
    t.value, t.work                                # estimate and engine departures

Semantics match Gymnasium's FrozenLake exactly: Discrete observations and actions, reward 1.0 on arriving at the
goal (else 0.0), ``terminated`` on arriving at a hole or the goal, and ``truncated`` always False in the raw env (as
in Gymnasium, the time limit is a ``TimeLimit`` wrapper: ``gym.make("ParaDEVS/FrozenLake8x8-v0")`` adds 200 steps,
like ``FrozenLake8x8-v1``). Stepping from a terminal state stays there with reward 0. ``info["prob"]`` is
P(s'|s,a) summed over the slip directions that lead to s' (Gymnasium reports the probability of the single slip
direction it drew, 1/3).

The random walk (Sutton and Barto, 19 states) has one action (Discrete(1)); reward -1 on arriving at 0, +1 at 20.
"""
from __future__ import annotations

import os
import time
from pathlib import Path
from typing import NamedTuple, Optional, Protocol, Sequence, Union, runtime_checkable

import numpy as np
import gymnasium as gym
from gymnasium import spaces

# ---------------------------------------------------------------------------------------------- JVM start-up

_REPO_ROOT = Path(__file__).resolve().parents[2]


def default_classpath(root: Optional[Union[str, Path]] = None) -> list[str]:
    """build/library/classes and lib/ms4-core.jar of the ParaDEVS checkout (env var PARADEVS_HOME overrides)."""
    root = Path(root or os.environ.get("PARADEVS_HOME") or _REPO_ROOT)
    return [str(root / "build" / "library" / "classes"), str(root / "lib" / "ms4-core.jar")]


def start_jvm(classpath: Optional[Sequence[str]] = None, max_heap: str = "512m", extra_args: Sequence[str] = ()):
    """Start the JVM once per process (later calls are no-ops). Build the Java side first (scripts/build_library.sh)."""
    import jpype
    if jpype.isJVMStarted():
        return
    cp = list(classpath or default_classpath())
    classes = Path(cp[0])
    if not (classes / "examples" / "rl" / "env" / "MdpSession.class").exists():
        raise FileNotFoundError(f"{classes}: MdpSession.class not found; run `bash scripts/build_library.sh` first")
    jpype.startJVM(f"-Xmx{max_heap}", *extra_args, classpath=cp, convertStrings=False)


def jvm_started() -> bool:
    import jpype
    return jpype.isJVMStarted()


def _jclass(name: str):
    start_jvm()
    import jpype
    return jpype.JClass(name)


def _jdoubles(x) -> "jpype.JArray":
    import jpype
    return jpype.JArray.of(np.ascontiguousarray(x, dtype=np.float64))     # buffer copy: ~2x faster than JArray(JDouble)


def _jints(x) -> "jpype.JArray":
    import jpype
    return jpype.JArray.of(np.ascontiguousarray(np.atleast_1d(x), dtype=np.int32))


# ---------------------------------------------------------------------------------------------- results


class Target(NamedTuple):
    """One n-step target: the estimate and the work (engine departures, including replays)."""
    value: float
    work: int


class PlanResult(NamedTuple):
    """Per-action values Q_n(s, a) (NaN where a planner skips an action) and the total work."""
    values: np.ndarray
    work: int


class Snapshot(NamedTuple):
    """Everything needed to resume a session exactly: the state, the step count and the RNG state."""
    state: int
    steps: int
    rng: int


# ---------------------------------------------------------------------------------------------- planner extension point


@runtime_checkable
class Planner(Protocol):
    """
    Online action selection: values for every action at ``state`` from a depth-``n`` lookahead bootstrapped with V.

    Contract: return a PlanResult whose ``values`` has length ``env.action_space.n``; must not change the env's live
    state or its step RNG (use ``seed``). ``rule``/``merge``/``k``/``budget``/``roots`` mean what they mean in
    ``ParaEnv.para_eval``. A planner may also be a Java ``examples.rl.env.ActionPlanner`` (see ``ParaEnv.planner``).
    """

    def action_values(self, env: "ParaEnv", state: int, n: int, V: np.ndarray, *, rule: str, merge: str,
                      k: Optional[int], budget: Optional[int], roots: int, seed: int) -> PlanResult: ...


class JavaPlanner:
    """Adapter: whatever ``ActionPlanner`` is installed in the Java session (default ``PolicyLookaheadPlanner``)."""

    def __init__(self, java_planner=None):
        self.java_planner = java_planner   # None: keep the session's current planner

    def action_values(self, env, state, n, V, *, rule, merge, k, budget, roots, seed):
        sess = env.session
        if self.java_planner is not None and not sess.planner().equals(self.java_planner):
            sess.setPlanner(self.java_planner)
        out = np.asarray(sess.plan(int(state), int(n), _jdoubles(V), rule, _param(rule, k, budget), merge,
                                   int(roots), int(seed)), dtype=float)
        return PlanResult(out[:-1].copy(), int(out[-1]))


def _param(rule: str, k: Optional[int], budget: Optional[int]) -> int:
    if rule == "mc":
        if k is None:
            raise ValueError("rule='mc' needs k (branches per draw)")
        return int(k)
    if rule == "budget":
        if budget is None:
            raise ValueError("rule='budget' needs budget (B samples per draw level)")
        return int(budget)
    if rule not in ("exhaustive", "rollouts"):
        raise ValueError(f"unknown rule {rule!r}: exhaustive, mc, budget, rollouts")
    return 0


# ---------------------------------------------------------------------------------------------- the environment

PROBLEMS = {"frozenlake8x8": "frozenLake8x8", "randomwalk19": "randomWalk19"}


class ParaEnv(gym.Env):
    """
    Gymnasium environment over a Java ``examples.rl.env.MdpSession``.

    Parameters
    ----------
    problem : "frozenlake8x8" (Gymnasium's slippery 8x8 map) or "randomwalk19".
    gamma : discount used by the engine targets and the DP helpers (FrozenLake; the random walk uses 1).
    policy : the policy targets are computed under: None (uniform), "eps_soft_optimal" (with ``eps``), or an
        array pi[s, a].
    """

    metadata = {"render_modes": [], "render_fps": 4}

    def __init__(self, problem: str = "frozenlake8x8", gamma: float = 0.99, policy=None, eps: float = 0.2,
                 render_mode: Optional[str] = None):
        if problem not in PROBLEMS:
            raise ValueError(f"unknown problem {problem!r}; choose from {sorted(PROBLEMS)}")
        if render_mode is not None:
            raise ValueError("ParaEnv is render-free (render_mode must be None)")
        self.render_mode = None
        self.problem = problem
        MdpSession = _jclass("examples.rl.env.MdpSession")
        self.session = MdpSession(PROBLEMS[problem], float(gamma))
        self.nS, self.nA = int(self.session.nS()), int(self.session.nA())
        self.gamma = float(self.session.gamma())
        self.start_state = int(self.session.startState())
        # Read the model once; step() then needs a single JPype call.
        self.P = np.array(self.session.transitionMatrix(), dtype=float).reshape(self.nA, self.nS, self.nS)
        self.reward_on_arrival = np.array(self.session.rewards(), dtype=float)
        self.terminal = np.array([bool(t) for t in self.session.terminals()])
        self.observation_space = spaces.Discrete(self.nS)
        self.action_space = spaces.Discrete(self.nA)
        self._s = self.start_state
        self._java_seeded = False
        self._planner: Planner = JavaPlanner()
        if isinstance(policy, str):
            if policy != "eps_soft_optimal":
                raise ValueError("policy must be None, 'eps_soft_optimal' or an array pi[s, a]")
            self.set_eps_soft_optimal(eps)
        elif policy is not None:
            self.set_policy(policy)

    # ------------------------------------------------------------------ Gymnasium API

    def reset(self, *, seed: Optional[int] = None, options: Optional[dict] = None):
        """Reset to the start state. ``options={"state": s}`` starts in s instead (exploring starts)."""
        super().reset(seed=seed)
        if seed is not None or not self._java_seeded:
            # The Java step RNG is seeded from np_random, so reset(seed=x) is fully reproducible.
            self.session.reset(int(self.np_random.integers(0, 2 ** 62)))
            self._java_seeded = True
        else:
            self.session.reset()
        self._s = self.start_state
        if options and "state" in options:
            self._s = int(options["state"])
            self.session.setState(self._s)
        return self._s, {"prob": 1}

    def step(self, action):
        a = int(action)
        if not 0 <= a < self.nA:
            raise ValueError(f"action {action} out of range")
        s = self._s
        s2 = int(self.session.step(a))
        self._s = s2
        if self.terminal[s]:
            return s2, 0.0, True, False, {"prob": 1.0}
        return s2, float(self.reward_on_arrival[s2]), bool(self.terminal[s2]), False, {"prob": float(self.P[a, s, s2])}

    def render(self):
        return None

    def close(self):
        if getattr(self, "session", None) is not None and jvm_started():
            self.session.close()
            self.session = None

    # ------------------------------------------------------------------ state access

    @property
    def state(self) -> int:
        return self._s

    def snapshot(self) -> Snapshot:
        st, steps, rng = (int(x) for x in self.session.snapshot())
        return Snapshot(st, steps, rng)

    def restore(self, snap: Snapshot):
        import jpype
        self.session.restore(jpype.JArray(jpype.JLong)([int(snap.state), int(snap.steps), int(snap.rng)]))
        self._s = int(snap.state)

    def sample_transitions(self, state: int, action: int, count: int) -> np.ndarray:
        """``count`` draws from P(.|state, action) with the env's RNG, in one Java call (live state unchanged)."""
        return np.array(self.session.sampleTransitions(int(state), int(action), int(count)), dtype=np.int64)

    def step_batch(self, actions) -> np.ndarray:
        """Apply a sequence of actions in one Java call; returns the next state after each."""
        out = np.array(self.session.stepBatch(_jints(actions)), dtype=np.int64)
        if out.size:
            self._s = int(out[-1])
        return out

    # ------------------------------------------------------------------ policy and exact DP (Java)

    @property
    def policy(self) -> np.ndarray:
        return np.array(self.session.policy(), dtype=float).reshape(self.nS, self.nA)

    def set_policy(self, pi):
        pi = np.asarray(pi, dtype=float)
        if pi.shape != (self.nS, self.nA):
            raise ValueError(f"policy must have shape {(self.nS, self.nA)}")
        self.session.setPolicy(_jdoubles(pi.ravel()))

    def set_eps_soft_optimal(self, eps: float = 0.2):
        """Epsilon-soft greedy policy for V* (ties to the lowest action), as TabularMdp.epsilonSoftOptimal."""
        self.session.setEpsilonSoftOptimal(float(eps))

    def evaluate_policy(self) -> np.ndarray:
        return np.array(self.session.evaluatePolicy(), dtype=float)

    def value_iteration(self) -> np.ndarray:
        return np.array(self.session.valueIteration(), dtype=float)

    # ------------------------------------------------------------------ engine targets

    def para_eval(self, state, n: int, V, rule: str = "exhaustive", merge: str = "safe", k: Optional[int] = None,
                  budget: Optional[int] = None, roots: int = 1, seed: int = 0, mode: str = "joint"):
        """
        n-step target  E[ sum_{i<n} gamma^i R_{i+1} + gamma^n V(S_n) | S_0 = state ]  under the env's policy,
        computed by the engine (examples.rl.NStepTargets.target). V is 0 at terminal states automatically.

        rule:  "exhaustive" (exact) | "mc" (k i.i.d. branches per draw) | "budget" (B samples per draw level,
               allocated by merged weight) | "rollouts" (one branch per draw; ``roots`` = number of rollouts).
        merge: "safe" (default) or "none".  mode: "joint" (one draw per step) or "separate" (action, transition).
        state: an int (returns a Target) or a sequence of ints (one batched Java call; returns (values, work)
               arrays, item i seeded seed + i). A Snapshot is accepted as a state.
        """
        param = _param(rule, k, budget)
        if rule == "rollouts" and roots < 1:
            raise ValueError("rollouts need roots >= 1")
        jV = _jdoubles(V)
        if isinstance(state, Snapshot):
            state = state.state
        if np.ndim(state) == 0:
            out = self.session.target(int(state), int(n), jV, rule, param, merge, mode, int(roots), int(seed))
            return Target(float(out[0]), int(out[1]))
        out = np.array(self.session.targetsBatch(_jints(state), int(n), jV, rule, param, merge, mode,
                                                   int(roots), int(seed)), dtype=float)
        return out[0::2].copy(), out[1::2].astype(np.int64)

    def para_eval_replications(self, state: int, n: int, V, reps: int, rule: str = "exhaustive",
                               merge: str = "safe", k=None, budget=None, roots: int = 1, seed: int = 0,
                               mode: str = "joint"):
        """``reps`` independent replications from one state (seeds seed..seed+reps-1) in one Java call."""
        out = np.array(self.session.targetReplications(int(state), int(n), _jdoubles(V), rule,
                                                         _param(rule, k, budget), merge, mode, int(roots),
                                                         int(seed), int(reps)), dtype=float)
        return out[0::2].copy(), out[1::2].astype(np.int64)

    # ------------------------------------------------------------------ planner extension point

    @property
    def planner(self) -> Planner:
        return self._planner

    @planner.setter
    def planner(self, p):
        """A Python Planner, or a Java examples.rl.env.ActionPlanner (wrapped in JavaPlanner)."""
        if isinstance(p, Planner):
            self._planner = p
        else:
            self._planner = JavaPlanner(p)

    def plan(self, n: int, V, state: Optional[int] = None, rule: str = "exhaustive", merge: str = "safe",
             k: Optional[int] = None, budget: Optional[int] = None, roots: int = 1, seed: int = 0) -> PlanResult:
        """Per-action values at ``state`` (default: the current state) from the installed planner."""
        s = self._s if state is None else int(state)
        return self._planner.action_values(self, s, int(n), np.asarray(V, dtype=float), rule=rule, merge=merge,
                                           k=k, budget=budget, roots=int(roots), seed=int(seed))


def para_eval(env: ParaEnv, state, n, V, rule="exhaustive", merge="safe", k=None, budget=None, roots=1, seed=0,
              mode="joint"):
    """Module-level form of ``ParaEnv.para_eval``."""
    return env.para_eval(state, n, V, rule, merge, k=k, budget=budget, roots=roots, seed=seed, mode=mode)


# ---------------------------------------------------------------------------------------------- helpers


class OneHot(gym.ObservationWrapper):
    """Discrete(n) observation -> one-hot float32 Box(n), for neural-network learners (e.g. SB3 DQN/PPO)."""

    def __init__(self, env):
        super().__init__(env)
        self.n = env.observation_space.n
        self.observation_space = spaces.Box(0.0, 1.0, (self.n,), np.float32)

    def observation(self, obs):
        x = np.zeros(self.n, dtype=np.float32)
        x[int(obs)] = 1.0
        return x


def measure_overhead(env: ParaEnv, calls: int = 20000, rounds: int = 5) -> dict:
    """
    Wall-clock microseconds: a no-op Java call; env.step; a 1000-step step_batch (and per step inside it); converting
    V to a Java array; an n=1 exhaustive target as one call per state versus inside one batched call over all
    nonterminal states (median of ``rounds``); their difference is the per-call cost of crossing JPype.
    """
    sess = env.session
    t = time.perf_counter()
    for _ in range(calls):
        sess.state()
    noop = (time.perf_counter() - t) / calls * 1e6
    env.reset(seed=0)
    t = time.perf_counter()
    for _ in range(calls):
        _, _, term, _, _ = env.step(1)
        if term:
            env.reset()
    step = (time.perf_counter() - t) / calls * 1e6
    acts = np.ones(1000, dtype=np.int32)
    reps = max(1, calls // 1000)
    t = time.perf_counter()
    for _ in range(reps):
        env.reset(); env.step_batch(acts)
    batch = (time.perf_counter() - t) / reps * 1e6
    V = np.zeros(env.nS)
    t = time.perf_counter()
    for _ in range(calls):
        _jdoubles(V)
    v_conv = (time.perf_counter() - t) / calls * 1e6
    states = np.flatnonzero(~env.terminal)
    single, batched = [], []
    for _ in range(rounds):                                  # interleaved, to share JIT and machine-load effects
        t = time.perf_counter()
        for s in states:
            env.para_eval(int(s), 1, V)
        single.append((time.perf_counter() - t) / len(states) * 1e6)
        t = time.perf_counter()
        env.para_eval(states, 1, V)
        batched.append((time.perf_counter() - t) / len(states) * 1e6)
    single_t, batch_t = float(np.median(single)), float(np.median(batched))
    return {"noop_call_us": noop, "step_us": step, "step_batch_1000_us": batch, "step_in_batch_us": batch / 1000,
            "V_to_java_us": v_conv, "target_n1_single_us": single_t, "target_n1_in_batch_us": batch_t,
            "target_call_overhead_us": single_t - batch_t}


def _register():
    for env_id, kwargs, steps in (("ParaDEVS/FrozenLake8x8-v0", {"problem": "frozenlake8x8"}, 200),
                                  ("ParaDEVS/RandomWalk19-v0", {"problem": "randomwalk19"}, None)):
        if env_id not in gym.registry:
            gym.register(env_id, entry_point="paratemporal_py.para_env:ParaEnv", kwargs=kwargs,
                         max_episode_steps=steps)


_register()
