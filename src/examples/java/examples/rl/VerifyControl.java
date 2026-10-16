package examples.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

import com.ms4systems.devs.core.model.AtomicModel;
import com.ms4systems.devs.core.simulation.Simulation;
import com.ms4systems.devs.core.simulation.impl.SimulationImpl;

import examples.Check;
import paradevs.Para;
import paradevs.engine.DecisionRule;
import paradevs.engine.LeafValues;
import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.Plan;
import paradevs.engine.PlanSpec;
import paradevs.engine.Planner;
import paradevs.engine.SelectionRule;
import paradevs.engine.Transducer;

/**
 * Phase 4c checks for the graph planner and Para.decide (build test "control"), against exact
 * dynamic programming on the 19-state random walk and slippery FrozenLake 8x8
 * (docs/rl/ParaDEVS_Control_Spec.pdf, Section "Verification").
 *
 * <ol>
 * <li>Depth-n planning with V = 0 and a max at decisions equals n steps of value iteration, from
 * every state, to 1e-12; so do the Q-values. Also with a no-op action and with random step
 * durations (semi-MDP, discount gamma^tau).</li>
 * <li>Policy evaluation: engine Q^pi equals DP Q^pi, and the greedy improvement agrees.</li>
 * <li>Policy iteration and value iteration driven by engine backups reach V* and an optimal policy.</li>
 * <li>Para.decide under the forward engine (decisions as policy draws) gives the same n-step value.</li>
 * <li>Work: the merged graph against the plain tree.</li>
 * <li>Sampled chance nodes: the policy-weighted value is unbiased; the sampled max is biased upward.</li>
 * <li>Online action selection on FrozenLake under plain MS4 simulation.</li>
 * </ol>
 */
public class VerifyControl {

    static final double TOL = 1e-12;

    public static void main(String[] args) {
        Check c = new Check("control: graph planner, Para.decide, value and policy iteration");
        long t0 = System.currentTimeMillis();

        TabularMdp rw = TabularMdp.randomWalk19();
        TabularMdp fl = TabularMdp.frozenLake8x8(0.99);
        TabularMdp flNoop = TabularMdp.withNoop(fl);

        section("-- 1. depth-n planning = n steps of value iteration (V = 0 leaves, max at decisions)");
        for (int n : new int[] {1, 5, 12}) viCheck(c, "RW", rw, n, false, null, null, 1.0, PlanSpec.TimeKey.ABSOLUTE);
        for (int n : new int[] {1, 3, 10}) viCheck(c, "FL", fl, n, false, null, null, 0.99, PlanSpec.TimeKey.ABSOLUTE);
        viCheckNoop(c, fl, flNoop, 4);
        double[] dur = {0.5, 1.0, 2.0}, durP = {0.25, 0.5, 0.25};
        Plan.Stats rel = viCheck(c, "FL semi-MDP, relative key", fl, 4, false, dur, durP, 0.9, PlanSpec.TimeKey.RELATIVE);
        Plan.Stats abs = viCheck(c, "FL semi-MDP, absolute key", fl, 4, false, dur, durP, 0.9, PlanSpec.TimeKey.ABSOLUTE);
        System.out.printf("  semi-MDP n=4: relative key %d nodes / %d departures, absolute key %d nodes / %d departures%n",
            rel.nodes(), rel.departures, abs.nodes(), abs.departures);
        c.equal("relative time key merges more than absolute under random durations", rel.nodes() < abs.nodes(), true);

        section("-- 2. policy evaluation and greedy improvement (uniform policy)");
        improvementCheck(c, fl);

        section("-- 3. policy iteration and value iteration driven by engine backups");
        iterationChecks(c, fl);

        section("-- 4. Para.decide under the forward engine");
        forwardEngineCheck(c, fl);

        section("-- 5. work: merged graph vs tree");
        workCheck(c, fl);

        section("-- 6. sampled chance nodes");
        sampledChecks(c, fl);

        section("-- 7. online action selection (plain MS4 simulation, lookahead policy)");
        OnlineDemo.run(c, fl);

        System.out.printf("  (control checks took %.1f s)%n", (System.currentTimeMillis() - t0) / 1000.0);
        c.finish();
    }

    static long mark = System.currentTimeMillis();
    static void section(String title) {
        long now = System.currentTimeMillis();
        System.out.printf("  (%.1f s)%n%s%n", (now - mark) / 1000.0, title);
        mark = now;
    }

    // ------------------------------------------------------------------ helpers

    static List<Supplier<AtomicModel>> roots(TabularMdp m, boolean noop, double[] dur, double[] durP) {
        List<Supplier<AtomicModel>> r = new ArrayList<>();
        for (int s = 0; s < m.nS; s++) { final int s0 = s; r.add(() -> new MdpAgent(m.name, s0, -1, noop, dur, durP)); }
        return r;
    }

    /** pi as a Para.Policy (observations are states; the no-op, if offered, is action nA of {@code table}). */
    static Para.Policy tablePolicy(double[][] table) {
        return (label, obs, options) -> {
            double[] row = table[(Integer) obs], p = new double[options.size()];
            for (int i = 0; i < p.length; i++) p[i] = options.get(i) == Para.NOOP ? row[row.length - 1] : row[(Integer) options.get(i)];
            return p;
        };
    }

    static double beta(double gamma, double[] dur, double[] durP) {
        if (dur == null) return gamma;
        double b = 0;
        for (int i = 0; i < dur.length; i++) b += durP[i] * Math.pow(gamma, dur[i]);
        return b;
    }

    static Plan.Stats viCheck(Check c, String what, TabularMdp m, int n, boolean noop, double[] dur, double[] durP,
                              double gamma, PlanSpec.TimeKey key) {
        Plan plan = Planner.expandFresh(PlanSpec.depth(n).gamma(gamma).timeKey(key), roots(m, noop, dur, durP));
        Plan.Values v = plan.backup(DecisionRule.max(), LeafValues.zero());
        double beta = beta(gamma, dur, durP);
        double[] vn = m.valueIterationSteps(n, new double[m.nS], beta);
        double[] vprev = m.valueIterationSteps(n - 1, new double[m.nS], beta);
        double errV = 0, errQ = 0;
        for (int s = 0; s < m.nS; s++) {
            errV = Math.max(errV, Math.abs(v.value(s) - vn[s]));
            if (m.isTerminal(s)) { if (v.hasDecision(s)) errQ = Double.POSITIVE_INFINITY; continue; }
            double[] q = v.q(s);
            for (int a = 0; a < m.nA; a++) errQ = Math.max(errQ, Math.abs(q[a] - m.q(vprev, s, a, beta)));
        }
        System.out.printf("  %-28s n=%-2d max|V-VI| %.1e  max|Q-DP| %.1e  %s%n", what, n, errV, errQ, plan.stats());
        c.close(what + " n=" + n + ": V = n-step VI at every state", errV, 0, TOL);
        c.close(what + " n=" + n + ": Q = DP at every state and action", errQ, 0, TOL);
        return plan.stats();
    }

    static void viCheckNoop(Check c, TabularMdp fl, TabularMdp flNoop, int n) {
        Plan plan = Planner.expandFresh(PlanSpec.depth(n).gamma(0.99), roots(fl, true, null, null));
        Plan.Values v = plan.backup(DecisionRule.max(), LeafValues.zero());
        double[] vn = flNoop.valueIterationSteps(n, new double[fl.nS], 0.99);
        double[] vprev = flNoop.valueIterationSteps(n - 1, new double[fl.nS], 0.99);
        double errV = 0, errQ = 0;
        for (int s = 0; s < fl.nS; s++) {
            errV = Math.max(errV, Math.abs(v.value(s) - vn[s]));
            if (fl.isTerminal(s)) continue;
            double[] q = v.q(s);
            List<?> opts = v.actions(s);
            for (int i = 0; i < q.length; i++) {
                int a = opts.get(i) == Para.NOOP ? fl.nA : (Integer) opts.get(i);
                errQ = Math.max(errQ, Math.abs(q[i] - flNoop.q(vprev, s, a, 0.99)));
            }
        }
        System.out.printf("  %-28s n=%-2d max|V-VI| %.1e  max|Q-DP| %.1e%n", "FL with no-op", n, errV, errQ);
        c.close("FL with no-op n=" + n + ": V and Q = DP on the MDP with a stay action", Math.max(errV, errQ), 0, TOL);
    }

    static void improvementCheck(Check c, TabularMdp fl) {
        double[][] uniform = new double[fl.nS][fl.nA];
        for (double[] row : uniform) Arrays.fill(row, 1.0 / fl.nA);
        fl.setPolicy(uniform);
        double[] vpi = fl.evaluatePolicy();
        Para.Policy pi = tablePolicy(uniform);
        Plan plan = Planner.expandFresh(PlanSpec.depth(2).gamma(0.99), roots(fl, false, null, null));
        Plan.Values v = plan.backup(DecisionRule.policy(pi), LeafValues.table(vpi));
        double errV = 0, errQ = 0;
        int agree = 0, states = 0;
        for (int s = 0; s < fl.nS; s++) {
            errV = Math.max(errV, Math.abs(v.value(s) - vpi[s]));
            if (fl.isTerminal(s)) continue;
            states++;
            double[] q = v.q(s), qdp = new double[fl.nA];
            for (int a = 0; a < fl.nA; a++) { qdp[a] = fl.q(vpi, s, a, 0.99); errQ = Math.max(errQ, Math.abs(q[a] - qdp[a])); }
            if (argmaxSet(q, 1e-9).equals(argmaxSet(qdp, 1e-9))) agree++;
        }
        System.out.printf("  uniform pi, depth 2, V^pi leaves: max|V-V^pi| %.1e  max|Q-Q^pi| %.1e  greedy sets agree %d/%d%n",
            errV, errQ, agree, states);
        c.close("engine V^pi (depth 2, policy backup) = DP V^pi", errV, 0, TOL);
        c.close("engine Q^pi = DP Q^pi", errQ, 0, TOL);
        c.equal("greedy improvement from engine Q^pi = DP improvement (all states)", agree, states);
    }

    static List<Integer> argmaxSet(double[] q, double tol) {
        double best = Double.NEGATIVE_INFINITY;
        for (double x : q) best = Math.max(best, x);
        List<Integer> l = new ArrayList<>();
        for (int a = 0; a < q.length; a++) if (q[a] >= best - tol) l.add(a);
        return l;
    }

    static void iterationChecks(Check c, TabularMdp fl) {
        double[] vstar = fl.valueIteration();
        Plan plan = Planner.expandFresh(PlanSpec.depth(1).gamma(0.99), roots(fl, false, null, null));
        System.out.println("  one depth-1 graph for all states: " + plan.stats());

        // Policy iteration: evaluate by repeated policy backups, improve greedily (keep the current action on ties).
        int[] act = new int[fl.nS];                  // pi_0: always Left
        double[] v = new double[fl.nS];
        int iterations = 0, sweeps = 0;
        boolean stable = false;
        while (!stable && iterations < 50) {
            iterations++;
            double[][] table = new double[fl.nS][fl.nA];
            for (int s = 0; s < fl.nS; s++) table[s][act[s]] = 1;
            DecisionRule rule = DecisionRule.policy(tablePolicy(table));
            for (int k = 0; k < 20000; k++) {
                double[] v2 = plan.backup(rule, LeafValues.table(v)).values();
                sweeps++;
                double d = 0;
                for (int s = 0; s < fl.nS; s++) d = Math.max(d, Math.abs(v2[s] - v[s]));
                v = v2;
                if (d < 1e-15) break;
            }
            Plan.Values qv = plan.backup(rule, LeafValues.table(v));
            stable = true;
            for (int s = 0; s < fl.nS; s++) {
                if (!qv.hasDecision(s)) continue;
                double[] q = qv.q(s);
                int best = act[s];
                for (int a = 0; a < fl.nA; a++) if (q[a] > q[best] + 1e-12) best = a;
                if (best != act[s]) { act[s] = best; stable = false; }
            }
        }
        double errV = 0, gap = 0;
        for (int s = 0; s < fl.nS; s++) {
            errV = Math.max(errV, Math.abs(v[s] - vstar[s]));
            if (fl.isTerminal(s)) continue;
            double best = Double.NEGATIVE_INFINITY;
            for (int a = 0; a < fl.nA; a++) best = Math.max(best, fl.q(vstar, s, a, 0.99));
            gap = Math.max(gap, best - fl.q(vstar, s, act[s], 0.99));
        }
        System.out.printf("  policy iteration: %d improvements, %d backups, max|V^pi - V*| %.1e, max optimality gap %.1e%n",
            iterations, sweeps, errV, gap);
        c.equal("engine-driven policy iteration stops (policy stable)", stable, true);
        c.close("policy iteration: V^pi = V* from valueIteration()", errV, 0, 1e-9);
        c.close("policy iteration: the final policy is greedy for V*", gap, 0, 1e-9);

        // Value iteration: repeated max backups on the same graph.
        double[] u = new double[fl.nS];
        int k;
        for (k = 1; k <= 20000; k++) {
            double[] u2 = plan.backup(DecisionRule.max(), LeafValues.table(u)).values();
            double d = 0;
            for (int s = 0; s < fl.nS; s++) d = Math.max(d, Math.abs(u2[s] - u[s]));
            u = u2;
            if (d < 1e-15) break;
        }
        double errU = 0;
        for (int s = 0; s < fl.nS; s++) errU = Math.max(errU, Math.abs(u[s] - vstar[s]));
        System.out.printf("  value iteration: %d backups, max|V - V*| %.1e%n", k, errU);
        c.close("engine-driven value iteration: V = V*", errU, 0, 1e-9);
    }

    /** xi for the forward engine: discounted reward so far, current state, time of the last report. */
    static final class Ret {
        final double g, t; final int s;
        Ret(double g, int s, double t) { this.g = g; this.s = s; this.t = t; }
        @Override public boolean equals(Object o) {
            return o instanceof Ret && Double.compare(g, ((Ret) o).g) == 0 && s == ((Ret) o).s && Double.compare(t, ((Ret) o).t) == 0;
        }
        @Override public int hashCode() { return Objects.hash(g, s, t); }
    }

    static void forwardEngineCheck(Check c, TabularMdp fl) {
        fl.epsilonSoftOptimal(0.2);
        double[] vpi = fl.evaluatePolicy();
        Para.Policy pi = tablePolicy(fl.policy());
        int s0 = 18, n = 3;
        double gamma = 0.99;
        Transducer<Ret> tr = new Transducer<Ret>() {
            @Override public Ret initial() { return new Ret(0, s0, -1); }
            @Override public Ret record(Ret xi, double t, List<OutputEvent> outs) {
                double g = xi.g; int s = xi.s; double tt = xi.t;
                for (OutputEvent e : outs) {
                    if (e.port.equals("reward")) g += Math.pow(gamma, t) * (Double) e.value;
                    if (e.port.equals("state")) { s = (Integer) e.value; tt = t; }
                }
                return new Ret(g, s, tt);
            }
        };
        Para.setPolicy(pi);
        ParaResult<Ret> r;
        try {
            r = ParaEngine.run(ParaSpec.of(() -> new MdpAgent(fl.name, s0, n, false, null, null), tr));
        } finally {
            Para.setPolicy(Para.Policy.UNIFORM);
        }
        double forward = r.expectation(x -> x.g + (fl.isTerminal(x.s) ? 0 : Math.pow(gamma, x.t + 1) * vpi[x.s]));
        Plan plan = Planner.expandFresh(PlanSpec.depth(n).gamma(gamma), List.of(() -> new MdpAgent(fl.name, s0)));
        double graph = plan.backup(DecisionRule.policy(pi), LeafValues.table(vpi)).value(0);
        System.out.printf("  s=18, n=3, eps-soft pi: forward engine %.15f, graph %.15f, V^pi %.15f (forward %s)%n",
            forward, graph, vpi[s0], r.stats());
        c.close("forward engine with decide-as-draw: n-step target = V^pi(18)", forward, vpi[s0], TOL);
        c.close("graph planner policy backup = forward engine", graph, forward, TOL);
    }

    static void workCheck(Check c, TabularMdp fl) {
        int s0 = 18;
        for (int n : new int[] {2, 4}) {
            PlanSpec base = PlanSpec.depth(n).gamma(0.99);
            Plan g = Planner.expandFresh(base, List.of(() -> new MdpAgent(fl.name, s0)));
            Plan t = Planner.expandFresh(PlanSpec.depth(n).gamma(0.99).merge(false), List.of(() -> new MdpAgent(fl.name, s0)));
            double vg = g.backup(DecisionRule.max(), LeafValues.zero()).value(0);
            double vt = t.backup(DecisionRule.max(), LeafValues.zero()).value(0);
            System.out.printf("  n=%d from s=18: graph %d departures, %d nodes; tree %d departures, %d nodes; ratio %.1fx%n",
                n, g.stats().departures, g.stats().nodes(), t.stats().departures, t.stats().nodes(),
                (double) t.stats().departures / g.stats().departures);
            c.close("n=" + n + ": graph value = tree value", vg, vt, TOL);
            if (n == 4) c.equal("n=4: graph needs at least 10x fewer departures than the tree",
                t.stats().departures >= 10 * g.stats().departures, true);
        }
    }

    static void sampledChecks(Check c, TabularMdp fl) {
        fl.epsilonSoftOptimal(0.2);
        double[] vpi = fl.evaluatePolicy();
        double[] vstar = fl.valueIteration();
        Para.Policy pi = tablePolicy(fl.policy());
        int s0 = 18, reps = 200;

        // Policy-weighted target: unbiased for any selection rule.
        unbiased(c, "policy backup, monteCarlo(3) + merge, n=3", fl, s0, 3, SelectionRule.monteCarlo(3), DecisionRule.policy(pi),
            LeafValues.table(vpi), vpi[s0], reps, false);
        unbiased(c, "policy backup, budget(12) + merge, n=3", fl, s0, 3, SelectionRule.budget(12), DecisionRule.policy(pi),
            LeafValues.table(vpi), vpi[s0], reps, false);
        // Semi-MDP with hybrid selection: transitions enumerated, durations sampled.
        double[] dur = {0.5, 1.0, 2.0}, durP = {0.25, 0.5, 0.25};
        Map<String, SelectionRule> byLabel = new HashMap<>();
        byLabel.put("duration", SelectionRule.monteCarlo(1));
        SelectionRule hybrid = SelectionRule.hybrid(byLabel, SelectionRule.exhaustive());
        PlanSpec exact = PlanSpec.depth(3).gamma(0.9).timeKey(PlanSpec.TimeKey.RELATIVE);
        double want = Planner.expandFresh(exact, List.of(() -> new MdpAgent(fl.name, s0, -1, false, dur, durP)))
            .backup(DecisionRule.policy(pi), LeafValues.table(vpi)).value(0);
        double sum = 0, sq = 0;
        for (int i = 0; i < reps; i++) {
            PlanSpec sp = PlanSpec.depth(3).gamma(0.9).timeKey(PlanSpec.TimeKey.RELATIVE).selection(hybrid).seed(5000 + i);
            double x = Planner.expandFresh(sp, List.of(() -> new MdpAgent(fl.name, s0, -1, false, dur, durP)))
                .backup(DecisionRule.policy(pi), LeafValues.table(vpi)).value(0);
            sum += x; sq += x * x;
        }
        double mean = sum / reps, se = Math.sqrt((sq - reps * mean * mean) / (reps - 1) / reps);
        System.out.printf("  %-46s mean %.6f exact %.6f se %.6f%n", "semi-MDP, hybrid (durations sampled), n=3", mean, want, se);
        c.equal("semi-MDP hybrid selection: policy-weighted value unbiased (4 s.e.)", Math.abs(mean - want) <= 4 * se + 1e-12, true);

        // Max backup: exact with exhaustive chance nodes, biased upward when sampled (optimizer's curse).
        double exhaustive = Planner.expandFresh(PlanSpec.depth(3).gamma(0.99), List.of(() -> new MdpAgent(fl.name, s0)))
            .backup(DecisionRule.max(), LeafValues.table(vstar)).value(0);
        c.close("max backup, exhaustive, V* leaves: value = V*(18) (no bias)", exhaustive, vstar[s0], TOL);
        double[] bias1 = unbiased(c, "max backup, monteCarlo(1), n=1", fl, s0, 1, SelectionRule.monteCarlo(1), DecisionRule.max(),
            LeafValues.table(vstar), vstar[s0], reps, true);
        unbiased(c, "max backup, monteCarlo(3), n=1", fl, s0, 1, SelectionRule.monteCarlo(3), DecisionRule.max(),
            LeafValues.table(vstar), vstar[s0], reps, true);
        unbiased(c, "max backup, monteCarlo(3), n=3", fl, s0, 3, SelectionRule.monteCarlo(3), DecisionRule.max(),
            LeafValues.table(vstar), vstar[s0], reps, true);
        c.equal("sampled max (k=1, n=1) is biased upward (mean - V* > 4 s.e.)", bias1[0] - vstar[s0] > 4 * bias1[1], true);

        // Double estimation at the root: select with one sampled plan, evaluate with an independent one.
        sum = 0; sq = 0;
        for (int i = 0; i < reps; i++) {
            Plan.Values a = Planner.expandFresh(PlanSpec.depth(1).gamma(0.99).selection(SelectionRule.monteCarlo(1)).seed(7000 + i),
                List.of(() -> new MdpAgent(fl.name, s0))).backup(DecisionRule.max(), LeafValues.table(vstar));
            Plan.Values b = Planner.expandFresh(PlanSpec.depth(1).gamma(0.99).selection(SelectionRule.monteCarlo(1)).seed(9000 + i),
                List.of(() -> new MdpAgent(fl.name, s0))).backup(DecisionRule.max(), LeafValues.table(vstar));
            double x = b.q(0)[a.greedyIndex(0)];
            sum += x; sq += x * x;
        }
        mean = sum / reps; se = Math.sqrt((sq - reps * mean * mean) / (reps - 1) / reps);
        System.out.printf("  %-46s mean %.6f V* %.6f bias %+.6f se %.6f%n", "double estimation at the root, k=1, n=1",
            mean, vstar[s0], mean - vstar[s0], se);
        c.equal("double estimation removes the upward bias (mean <= V* + 2 s.e.)", mean <= vstar[s0] + 2 * se, true);
    }

    /** Mean and s.e. of a sampled plan's root value over seeds; checks unbiasedness unless {@code biasOnly}. */
    static double[] unbiased(Check c, String what, TabularMdp fl, int s0, int n, SelectionRule rule, DecisionRule dr,
                             LeafValues leaf, double exact, int reps, boolean biasOnly) {
        double sum = 0, sq = 0, work = 0;
        for (int i = 0; i < reps; i++) {
            Plan p = Planner.expandFresh(PlanSpec.depth(n).gamma(0.99).selection(rule).seed(1000 + i),
                List.of(() -> new MdpAgent(fl.name, s0)));
            double x = p.backup(dr, leaf).value(0);
            sum += x; sq += x * x; work += p.stats().departures;
        }
        double mean = sum / reps, se = Math.sqrt((sq - reps * mean * mean) / (reps - 1) / reps);
        System.out.printf("  %-46s mean %.6f exact %.6f bias %+.6f se %.6f work %.0f%n", what, mean, exact, mean - exact, se, work / reps);
        if (!biasOnly) c.equal(what + ": unbiased (4 s.e.)", Math.abs(mean - exact) <= 4 * se + 1e-12, true);
        return new double[] {mean, se};
    }

    // ------------------------------------------------------------------ online demo

    /**
     * An agent acting by depth-n lookahead under plain MS4 simulation: Para.decide calls a
     * {@link Planner.LookaheadPolicy} (greedy, ties shared). Success = reaching the goal within
     * 200 decisions. The exact success probability of each induced policy is computed by DP.
     */
    static final class OnlineDemo {
        static void run(Check c, TabularMdp fl) {
            double[] vstar = fl.valueIteration();
            int episodes = 500, limit = 200;
            List<Object> states = new ArrayList<>();
            for (int s = 0; s < fl.nS; s++) states.add(s);
            Object[][] configs = { {"V* leaves", 1}, {"V* leaves", 3}, {"V=0 leaves", 1}, {"V=0 leaves", 4},
                                   {"V=0 leaves", 8}, {"V=0 leaves", 14}, {"V=0 leaves", 30} };
            double optimal = Double.NaN;

            // Default policy (uniform) under plain MS4: decide() samples, the episode runs to its end.
            java.io.PrintStream out0 = System.out;
            System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            MdpAgent plain = new MdpAgent(fl.name, fl.start, limit, false, null, null);
            try {
                Simulation sim = new SimulationImpl("FrozenLake, default policy", plain);
                sim.startSimulation(0);
                sim.simulateIterations(Long.MAX_VALUE);
            } finally {
                System.setOut(out0);
            }
            c.equal("plain MS4 run with the default policy ends (decide samples uniformly)", plain.phase(), MdpAgent.Phase.DONE);

            // Plan from a model in the middle of a plain MS4 run: same Q as a fresh model in that state.
            Para.setDefaultRandom(new Random(3));
            MdpAgent mid = null;
            for (int attempt = 0; attempt < 50 && mid == null; attempt++) {
                MdpAgent m = new MdpAgent(fl.name, fl.start, -1, false, null, null);
                System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
                try {
                    Simulation sim = new SimulationImpl("FrozenLake, partial run", m);
                    sim.startSimulation(0);
                    sim.simulateIterations(6);     // three decisions, then stop between events
                } finally {
                    System.setOut(out0);
                }
                if (m.phase() == MdpAgent.Phase.DECIDE) mid = m;
            }
            double now = mid.getSimulator().getLastEventTime();
            final int sMid = mid.state();
            PlanSpec sp = PlanSpec.depth(3).gamma(0.99);
            double[] qSnap = Planner.expand(sp, List.of(Planner.Root.snapshot(mid, now)))
                .backup(DecisionRule.max(), LeafValues.table(vstar)).q(0);
            double[] qFresh = Planner.expandFresh(sp, List.of(() -> new MdpAgent(fl.name, sMid)))
                .backup(DecisionRule.max(), LeafValues.table(vstar)).q(0);
            double errSnap = 0;
            for (int a = 0; a < fl.nA; a++) errSnap = Math.max(errSnap, Math.abs(qSnap[a] - qFresh[a]));
            System.out.printf("  snapshot root at t=%.0f, state %d: max|Q(snapshot) - Q(fresh)| %.1e%n", now, sMid, errSnap);
            c.close("plan from a running MS4 model (snapshot root) = plan from a fresh model", errSnap, 0, TOL);

            for (Object[] cfg : configs) {
                boolean useStar = cfg[0].equals("V* leaves");
                int n = (Integer) cfg[1];
                Planner.LookaheadPolicy pol = Planner.lookahead(PlanSpec.depth(n).gamma(0.99),
                    o -> new MdpAgent(fl.name, (Integer) o), DecisionRule.max(),
                    useStar ? LeafValues.table(vstar) : LeafValues.zero(), 0.0, true);
                long t1 = System.currentTimeMillis();
                pol.prepare(states);                 // one batched plan for all 64 states
                long planMs = System.currentTimeMillis() - t1;
                double[][] probs = new double[fl.nS][];
                for (int s = 0; s < fl.nS; s++) if (!fl.isTerminal(s)) probs[s] = pol.probabilities("action", s, MdpAgent.optionList(fl.nA, false));
                double exact = successProbability(fl, probs, limit);
                Para.setPolicy(pol);
                Para.setDefaultRandom(new Random(4242 + n));
                int wins = 0;
                java.io.PrintStream out = System.out;
                System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));   // MS4 logs every event
                try {
                    for (int e = 0; e < episodes; e++) {
                        MdpAgent agent = new MdpAgent(fl.name, fl.start, limit, false, null, null);
                        Simulation sim = new SimulationImpl("FrozenLake episode", agent);
                        sim.startSimulation(0);
                        sim.simulateIterations(Long.MAX_VALUE);
                        if (fl.rewardOnArrival(agent.state()) > 0) wins++;
                    }
                } finally {
                    System.setOut(out);
                    Para.setPolicy(Para.Policy.UNIFORM);
                }
                double rate = (double) wins / episodes, se = Math.sqrt(exact * (1 - exact) / episodes);
                double[] q0 = pol.q(fl.start);
                System.out.printf("  %-10s n=%-2d success %.3f (exact %.4f, se %.3f)  e(a) at start [%.4f %.4f %.4f %.4f]  plan %d ms, %d departures%n",
                    cfg[0], n, rate, exact, se, q0[0], q0[1], q0[2], q0[3], planMs, pol.departures);
                c.equal(cfg[0] + " n=" + n + ": simulated success rate matches the exact rate (4 s.e.)",
                    Math.abs(rate - exact) <= 4 * se + 1e-9, true);
                if (useStar && n == 1) optimal = exact;
                if (useStar) c.close("V* leaves n=" + n + ": exact success = optimal policy's", exact, optimal, 1e-9);
            }
            c.equal("lookahead with V*-leaves beats V=0 leaves at small depth", optimal > successAt(fl, 1, limit) + 0.3, true);
        }

        static double successAt(TabularMdp fl, int n, int limit) {
            Planner.LookaheadPolicy pol = Planner.lookahead(PlanSpec.depth(n).gamma(0.99), o -> new MdpAgent(fl.name, (Integer) o),
                DecisionRule.max(), LeafValues.zero(), 0.0, true);
            double[][] probs = new double[fl.nS][];
            for (int s = 0; s < fl.nS; s++) if (!fl.isTerminal(s)) probs[s] = pol.probabilities("action", s, MdpAgent.optionList(fl.nA, false));
            return successProbability(fl, probs, limit);
        }

        /** P(reach the goal within {@code limit} decisions from the start) under pi[s][a]. */
        static double successProbability(TabularMdp fl, double[][] pi, int limit) {
            double[] p = new double[fl.nS];
            for (int k = 0; k < limit; k++) {
                double[] p2 = new double[fl.nS];
                for (int s = 0; s < fl.nS; s++) {
                    if (fl.isTerminal(s)) continue;
                    double x = 0;
                    for (int a = 0; a < fl.nA; a++) {
                        if (pi[s][a] == 0) continue;
                        for (int s2 = 0; s2 < fl.nS; s2++) {
                            double pr = fl.p[s][a][s2];
                            if (pr == 0) continue;
                            x += pi[s][a] * pr * (fl.rewardOnArrival(s2) > 0 ? 1 : fl.isTerminal(s2) ? 0 : p[s2]);
                        }
                    }
                    p2[s] = x;
                }
                p = p2;
            }
            return p[fl.start];
        }
    }
}
