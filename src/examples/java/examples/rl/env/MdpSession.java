package examples.rl.env;

import java.util.concurrent.atomic.AtomicLong;

import examples.rl.MdpWalker;
import examples.rl.NStepTargets;
import examples.rl.TabularMdp;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;

/**
 * A live environment over a {@link TabularMdp}, for the Python {@code ParaEnv} (through JPype) or
 * any other caller: reset, step with a seeded RNG, snapshot and restore, n-step targets from the
 * engine ({@link NStepTargets#target}), and action values from a pluggable {@link ActionPlanner}.
 *
 * <p>Each session owns a registered copy of the problem (its own policy), so sessions do not
 * interfere. Arguments that select engine options are plain strings and ints, and the batch
 * methods take and return primitive arrays, so that one JPype call does a lot of work.
 *
 * <p>Semantics follow Gymnasium's FrozenLake: the reward is the reward on arrival in the next
 * state; an episode terminates on arriving in a terminal state; stepping from a terminal state
 * stays there with reward 0. Truncation (time limits) is left to the caller, as in Gymnasium.
 *
 * <p>Step randomness: SplitMix64, seeded through the same SplitMix64 finalizer the engine uses
 * for its seeds, so nearby seeds give independent streams. Its whole state is one long, which
 * makes {@link #snapshot()} exact.
 */
public final class MdpSession {

    private static final AtomicLong IDS = new AtomicLong();

    public final TabularMdp mdp;
    public final String problem;
    private final int[][][] succ;      // succ[s][a]  successor states with P > 0, increasing
    private final double[][][] cum;    // cum[s][a]   cumulative probabilities for succ[s][a]

    private int state;
    private long steps;
    private long rng;                  // SplitMix64 state
    private ActionPlanner planner = new PolicyLookaheadPlanner();

    /**
     * @param problem "frozenLake8x8" or "randomWalk19"
     * @param gamma   discount for FrozenLake (the random walk always uses 1)
     */
    public MdpSession(String problem, double gamma) {
        TabularMdp base;
        switch (problem) {
            case "frozenLake8x8": base = TabularMdp.frozenLake8x8(gamma); break;
            case "randomWalk19": base = TabularMdp.randomWalk19(); break;
            default: throw new IllegalArgumentException("unknown problem '" + problem + "' (frozenLake8x8, randomWalk19)");
        }
        this.problem = problem;
        this.mdp = base.copy(problem + "#session" + IDS.incrementAndGet());
        int nS = mdp.nS, nA = mdp.nA;
        succ = new int[nS][nA][];
        cum = new double[nS][nA][];
        for (int s = 0; s < nS; s++)
            for (int a = 0; a < nA; a++) {
                int k = 0;
                for (int s2 = 0; s2 < nS; s2++) if (mdp.transitionProb(s, a, s2) > 0) k++;
                succ[s][a] = new int[k]; cum[s][a] = new double[k];
                double c = 0; int i = 0;
                for (int s2 = 0; s2 < nS; s2++) {
                    double pr = mdp.transitionProb(s, a, s2);
                    if (pr > 0) { c += pr; succ[s][a][i] = s2; cum[s][a][i] = c; i++; }
                }
            }
        this.state = mdp.start;
        this.rng = scramble(0);
    }

    /** Unregister this session's MDP copy. The session must not be used afterwards. */
    public void close() { TabularMdp.unregister(mdp.name); }

    // ------------------------------------------------------------------ model description (read once)

    public int nS() { return mdp.nS; }
    public int nA() { return mdp.nA; }
    public double gamma() { return mdp.gamma; }
    public int startState() { return mdp.start; }

    public double[] rewards() {
        double[] r = new double[mdp.nS];
        for (int s = 0; s < mdp.nS; s++) r[s] = mdp.rewardOnArrival(s);
        return r;
    }

    public boolean[] terminals() {
        boolean[] t = new boolean[mdp.nS];
        for (int s = 0; s < mdp.nS; s++) t[s] = mdp.isTerminal(s);
        return t;
    }

    /** P flattened as [a][s][s'] (row-major), nA * nS * nS entries. */
    public double[] transitionMatrix() {
        int nS = mdp.nS, nA = mdp.nA;
        double[] out = new double[nA * nS * nS];
        for (int a = 0; a < nA; a++)
            for (int s = 0; s < nS; s++)
                for (int s2 = 0; s2 < nS; s2++) out[(a * nS + s) * nS + s2] = mdp.transitionProb(s, a, s2);
        return out;
    }

    // ------------------------------------------------------------------ policy

    /** pi flattened as [s][a]. */
    public double[] policy() {
        double[] out = new double[mdp.nS * mdp.nA];
        for (int s = 0; s < mdp.nS; s++) for (int a = 0; a < mdp.nA; a++) out[s * mdp.nA + a] = mdp.policyProb(s, a);
        return out;
    }

    /** Set pi from a flattened [s][a] array. */
    public void setPolicy(double[] flat) {
        if (flat.length != mdp.nS * mdp.nA) throw new IllegalArgumentException("policy needs nS * nA entries");
        double[][] pi = new double[mdp.nS][mdp.nA];
        for (int s = 0; s < mdp.nS; s++) System.arraycopy(flat, s * mdp.nA, pi[s], 0, mdp.nA);
        mdp.setPolicy(pi);
    }

    /** Epsilon-soft greedy policy for V* (ties to the lowest action). */
    public void setEpsilonSoftOptimal(double eps) { mdp.epsilonSoftOptimal(eps); }

    public double[] evaluatePolicy() { return mdp.evaluatePolicy(); }
    public double[] valueIteration() { return mdp.valueIteration(); }

    // ------------------------------------------------------------------ live state

    /** Reseed the step RNG and return to the start state. */
    public int reset(long seed) { rng = scramble(seed); return reset(); }

    /** Return to the start state, keeping the RNG stream. */
    public int reset() { state = mdp.start; steps = 0; return state; }

    public int state() { return state; }
    public long steps() { return steps; }

    /** Put the session in state s (e.g. for exploring starts); the RNG is unchanged. */
    public void setState(int s) {
        if (s < 0 || s >= mdp.nS) throw new IllegalArgumentException("state out of range");
        state = s;
    }

    /** {state, steps, rng}: everything needed to resume exactly. */
    public long[] snapshot() { return new long[] { state, steps, rng }; }

    public void restore(long[] snap) {
        if (snap.length != 3) throw new IllegalArgumentException("snapshot is {state, steps, rng}");
        setState((int) snap[0]); steps = snap[1]; rng = snap[2];
    }

    /** One step with action a; returns the next state (reward and termination follow from it). */
    public int step(int a) {
        if (a < 0 || a >= mdp.nA) throw new IllegalArgumentException("action out of range");
        steps++;
        if (mdp.isTerminal(state)) return state;
        state = sample(state, a);
        return state;
    }

    /** {next state, reward, terminated (0/1), P(next | s, a)} for callers that want it in one call. */
    public double[] stepFull(int a) {
        int s = state;
        int s2 = step(a);
        boolean fromTerminal = mdp.isTerminal(s);
        return new double[] { s2, fromTerminal ? 0 : mdp.rewardOnArrival(s2), mdp.isTerminal(s2) ? 1 : 0,
                              fromTerminal ? 1 : mdp.transitionProb(s, a, s2) };
    }

    /**
     * Apply actions in order; returns the next state after each. Once a terminal state is reached,
     * the remaining entries repeat it (as further steps would).
     */
    public int[] stepBatch(int[] actions) {
        int[] out = new int[actions.length];
        for (int i = 0; i < actions.length; i++) out[i] = step(actions[i]);
        return out;
    }

    /** {@code count} independent draws from P(.|s,a), using the session RNG (the live state is unchanged). */
    public int[] sampleTransitions(int s, int a, int count) {
        int[] out = new int[count];
        if (mdp.isTerminal(s)) { java.util.Arrays.fill(out, s); return out; }
        for (int i = 0; i < count; i++) out[i] = sample(s, a);
        return out;
    }

    private int sample(int s, int a) {
        double u = nextDouble();
        double[] c = cum[s][a];
        int[] to = succ[s][a];
        for (int i = 0; i < c.length - 1; i++) if (u < c[i]) return to[i];
        return to[c.length - 1];
    }

    private double nextDouble() {
        rng += 0x9E3779B97F4A7C15L;
        long z = rng;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    /** The SplitMix64 finalizer used by the engine for seeds. */
    static long scramble(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ------------------------------------------------------------------ engine targets

    /**
     * Selection rule from a name: "exhaustive"; "mc" (k branches per draw, {@code param} = k);
     * "budget" ({@code param} = B); "rollouts" (one branch per draw; use roots = N).
     */
    public static SelectionRule rule(String name, int param) {
        switch (name) {
            case "exhaustive": return SelectionRule.exhaustive();
            case "mc": return SelectionRule.monteCarlo(Math.max(1, param));
            case "budget": return SelectionRule.budget(Math.max(1, param));
            case "rollouts": return SelectionRule.monteCarlo();
            default: throw new IllegalArgumentException("unknown rule '" + name + "' (exhaustive, mc, budget, rollouts)");
        }
    }

    /** Merge mode by name (case-insensitive), e.g. "none" or "safe". */
    public static ParaSpec.Merge merge(String name) { return ParaSpec.Merge.valueOf(name.toUpperCase()); }

    /** Draw structure by name: "joint" (one draw per step) or "separate" (action, then transition). */
    public static MdpWalker.Mode mode(String name) { return MdpWalker.Mode.valueOf(name.toUpperCase()); }

    /**
     * The n-step target from state s under the session policy:
     * {value, departures (work), choiceNodes, merged, maxLive}.
     */
    public double[] target(int s, int n, double[] v, String rule, int param, String merge, String mode,
                           int roots, long seed) {
        NStepTargets.Estimate e = NStepTargets.target(mdp, s, n, v, mode(mode), rule(rule, param), merge(merge), roots, seed);
        return new double[] { e.value, e.departures, e.choiceNodes, e.merged, e.maxLive };
    }

    /** Targets for many states in one call: {value_0, work_0, value_1, work_1, ...}; item i uses seed + i. */
    public double[] targetsBatch(int[] states, int n, double[] v, String rule, int param, String merge, String mode,
                                 int roots, long seed) {
        SelectionRule r = rule(rule, param);
        ParaSpec.Merge m = merge(merge);
        MdpWalker.Mode md = mode(mode);
        double[] out = new double[2 * states.length];
        for (int i = 0; i < states.length; i++) {
            NStepTargets.Estimate e = NStepTargets.target(mdp, states[i], n, v, md, r, m, roots, seed + i);
            out[2 * i] = e.value; out[2 * i + 1] = e.departures;
        }
        return out;
    }

    /**
     * {@code reps} independent replications of the target from one state (seeds seed..seed+reps-1):
     * {value_0, work_0, ...}. For variance and bias studies.
     */
    public double[] targetReplications(int s, int n, double[] v, String rule, int param, String merge, String mode,
                                       int roots, long seed, int reps) {
        int[] states = new int[reps];
        java.util.Arrays.fill(states, s);
        return targetsBatch(states, n, v, rule, param, merge, mode, roots, seed);
    }

    // ------------------------------------------------------------------ planner extension point

    public void setPlanner(ActionPlanner p) { planner = java.util.Objects.requireNonNull(p); }
    public ActionPlanner planner() { return planner; }

    /** Action values at state s from the installed planner: {Q(s,0), ..., Q(s,nA-1), work}. */
    public double[] plan(int s, int n, double[] v, String rule, int param, String merge, int roots, long seed) {
        return planner.actionValues(mdp, s, n, v, rule(rule, param), merge(merge), roots, seed);
    }
}
