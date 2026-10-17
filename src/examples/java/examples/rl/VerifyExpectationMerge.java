package examples.rl;

import java.util.List;

import examples.Check;
import examples.reward.TimedReward;
import paradevs.engine.InputTrace;
import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;
import paradevs.engine.Transducer;

/**
 * Checks for the opt-in expectation-only (accumulator) merge, {@code ParaSpec.Merge.EXPECTATION}
 * (build test "expectation").
 *
 * <ol>
 * <li>Exhaustive selection: the same expectation as no merging and as safe merging, to 1e-12, on
 *   the timed reward example (discounted reward) and on n-step targets with a reward on every step
 *   (random walk and FrozenLake variants), where safe merging rarely fires.</li>
 * <li>It merges where safe merging cannot: leaves, merges and departures are reported and compared.</li>
 * <li>Unbiased with monteCarlo(k) and budget(B): mean of 200 seeded replications within 4 s.e.</li>
 * <li>Refusals: no declared reward split, N > 1 roots, and distribution-level queries.</li>
 * </ol>
 */
public class VerifyExpectationMerge {

    static final ParaSpec.Merge NONE = ParaSpec.Merge.NONE, SAFE = ParaSpec.Merge.SAFE, EXP = ParaSpec.Merge.EXPECTATION;
    static final MdpWalker.Mode JOINT = MdpWalker.Mode.JOINT;
    static final double BETA = 0.9;   // discount per time unit for the timed reward

    /** Timed reward: xi is the discounted emitted reward sum r * BETA^t; the rest is empty. */
    static final Transducer.Additive<Double> DISCOUNTED = new Transducer.Additive<Double>() {
        @Override public Double initial() { return 0.0; }
        @Override public Double record(Double xi, double t, List<OutputEvent> outs) {
            double g = xi;
            for (OutputEvent e : outs) if (e.port.equals("reward")) g += ((Integer) e.value) * Math.pow(BETA, t);
            return g;
        }
        @Override public double reward(Double xi) { return xi; }
        @Override public Double withReward(Double xi, double r) { return r; }
    };

    static ParaSpec<Double> timedSpec() {
        InputTrace trace = InputTrace.builder()
            .at(0.0, "action", "a").at(0.5, "action", "b").at(1.5, "action", "c").build();
        return ParaSpec.of(TimedReward::new, DISCOUNTED).trace(trace).horizon(5);
    }

    public static void main(String[] args) {
        Check c = new Check("Expectation-only (accumulator) merge");

        // ---------------------------------------------------------------- 1. timed reward
        // From the exact pair law (VerifyTimedReward): P(r=1 at 3/2) = 22700/40000, P(r=1 at 5/2) = 23855/40000.
        double exactTimed = Math.pow(BETA, 1.5) * 22700 / 40000.0 + Math.pow(BETA, 2.5) * 23855 / 40000.0;
        ParaResult<Double> tn = ParaEngine.run(timedSpec());
        ParaResult<Double> ts = ParaEngine.run(timedSpec().merge(SAFE));
        ParaResult<Double> te = ParaEngine.run(timedSpec().merge(EXP));
        System.out.println("  timed reward, discounted (exact " + exactTimed + ")");
        row("no merge", tn.stats()); row("safe merge", ts.stats()); row("expectation merge", te.stats());
        c.close("timed: no merge = exact", tn.expectedReward(), exactTimed, 1e-12);
        c.close("timed: safe merge = exact", ts.expectedReward(), exactTimed, 1e-12);
        c.close("timed: expectation merge = exact", te.expectedReward(), exactTimed, 1e-12);
        c.close("timed: expectation merge keeps total mass", te.totalMass(), 1.0, 1e-12);
        c.equal("timed: expectation merge leaves fewer branches than safe", te.stats().terminalLeaves < ts.stats().terminalLeaves, true);

        // ---------------------------------------------------------------- 2. random walk, reward on every step
        // Reward s'/100 on arriving in a non-terminal s', ends -1/+1, bootstrap V(s) = (s - 10)/10.
        // From 13 in 6 steps the walk cannot be absorbed and is a martingale: target = 6 * 0.13 + 0.3 = 1.08.
        TabularMdp rw0 = TabularMdp.randomWalk19();
        double[] bonus = new double[rw0.nS];
        for (int s = 0; s < rw0.nS; s++) if (!rw0.isTerminal(s)) bonus[s] = s / 100.0;
        TabularMdp rw = rw0.withArrivalBonus("randomWalk19Step", bonus);
        double[] vrw = rw0.evaluatePolicy();
        for (int n : new int[] {6, 10}) {
            NStepTargets.Estimate a = target(rw, 13, n, vrw, SelectionRule.exhaustive(), NONE, 1);
            NStepTargets.Estimate b = target(rw, 13, n, vrw, SelectionRule.exhaustive(), SAFE, 1);
            NStepTargets.Estimate e = target(rw, 13, n, vrw, SelectionRule.exhaustive(), EXP, 1);
            System.out.println("  random walk + step reward, start 13, n=" + n);
            row("no merge", a); row("safe merge", b); row("expectation merge", e);
            if (n == 6) c.close("RW n=6: no merge = 1.08", a.value, 1.08, 1e-12);
            c.close("RW n=" + n + ": safe merge = no merge", b.value, a.value, 1e-12);
            c.close("RW n=" + n + ": expectation merge = no merge", e.value, a.value, 1e-12);
            c.equal("RW n=" + n + ": expectation merge leaves fewer branches than safe", e.leaves < b.leaves, true);
            c.equal("RW n=" + n + ": expectation merge needs fewer departures than safe", e.departures < b.departures, true);
        }
        // The law of the rest (here: where the walk ends) stays exact: P(absorbed at 20 within 10 steps).
        {
            ParaResult<NStepTargets.Ret> a = rwRun(rw, 10, NONE), e = rwRun(rw, 10, EXP);
            c.close("RW n=10: P(end at 20) from the rest is exact",
                e.expectation(x -> 0, x -> x.state == 20 ? 1 : 0), a.expectation(x -> 0, x -> x.state == 20 ? 1 : 0), 1e-12);
        }

        // ---------------------------------------------------------------- 3. FrozenLake, reward on every step
        TabularMdp fl0 = TabularMdp.frozenLake8x8(0.99);
        double[] flBonus = new double[fl0.nS];
        for (int s = 0; s < fl0.nS; s++) if (!fl0.isTerminal(s)) flBonus[s] = s / 1000.0;
        TabularMdp fl = fl0.withArrivalBonus("frozenLake8x8Step", flBonus).epsilonSoftOptimal(0.2);
        double[] vfl = fl.evaluatePolicy();
        int s0 = 18;
        for (int n : new int[] {1, 2, 3}) {
            NStepTargets.Estimate a = target(fl, s0, n, vfl, SelectionRule.exhaustive(), NONE, 1);
            NStepTargets.Estimate b = target(fl, s0, n, vfl, SelectionRule.exhaustive(), SAFE, 1);
            NStepTargets.Estimate e = target(fl, s0, n, vfl, SelectionRule.exhaustive(), EXP, 1);
            if (n == 3) {
                System.out.println("  FrozenLake + step reward, start 18, n=3");
                row("no merge", a); row("safe merge", b); row("expectation merge", e);
                c.equal("FL n=3: expectation merge merges where safe merging finds nothing", b.merged == 0 && e.merged > 0 && e.leaves < b.leaves, true);
                c.equal("FL n=3: expectation merge needs fewer departures than safe", e.departures < b.departures, true);
            }
            c.close("FL n=" + n + ": no merge = V(18)", a.value, vfl[s0], 1e-12);
            c.close("FL n=" + n + ": safe merge = V(18)", b.value, vfl[s0], 1e-12);
            c.close("FL n=" + n + ": expectation merge = V(18)", e.value, vfl[s0], 1e-12);
        }

        // ---------------------------------------------------------------- 4. unbiased when sampled
        int reps = 200;
        unbiased(c, "RW k=3 tree, expectation merge", rw, 13, 6, vrw, SelectionRule.monteCarlo(3), reps, 1.08);
        unbiased(c, "RW budget B=9, expectation merge", rw, 13, 6, vrw, SelectionRule.budget(9), reps, 1.08);
        unbiased(c, "FL k=3 tree, expectation merge", fl, s0, 4, vfl, SelectionRule.monteCarlo(3), reps, vfl[s0]);
        unbiased(c, "FL budget B=20, expectation merge", fl, s0, 4, vfl, SelectionRule.budget(20), reps, vfl[s0]);

        // ---------------------------------------------------------------- 5. refusals
        c.equal("EXPECTATION without a declared reward split is refused", throwsIAE(() ->
            ParaSpec.of(TimedReward::new, Transducer.history()).merge(EXP)), true);
        c.equal("EXPECTATION with N > 1 Monte Carlo roots is refused", throwsIAE(() ->
            ParaEngine.run(timedSpec().merge(EXP).selection(SelectionRule.monteCarlo()).roots(10))), true);
        c.equal("result is flagged expectation-only", te.expectationOnly() && !ts.expectationOnly(), true);
        c.equal("distribution() is refused", throwsISE(te::distribution), true);
        c.equal("marginal(g) is refused", throwsISE(() -> te.marginal(x -> x)), true);
        c.equal("general expectation(f) is refused", throwsISE(() -> te.expectation(x -> x * x)), true);
        c.equal("distribution() still works under SAFE", !ts.distribution().isEmpty(), true);
        ParaResult<Transducer.History> hist = ParaEngine.run(ParaSpec.of(TimedReward::new, Transducer.history()).horizon(2));
        c.equal("expectation(slope, offset) without a reward split is refused", throwsISE(() ->
            hist.expectation(x -> 1, x -> 0)), true);

        c.finish();
    }

    static NStepTargets.Estimate target(TabularMdp mdp, int s, int n, double[] v, SelectionRule rule, ParaSpec.Merge m, long seed) {
        return NStepTargets.target(mdp, s, n, v, JOINT, rule, m, 1, seed);
    }

    static ParaResult<NStepTargets.Ret> rwRun(TabularMdp mdp, int n, ParaSpec.Merge m) {
        return ParaEngine.run(ParaSpec.of(() -> new MdpWalker(mdp.name, 13, n, JOINT), NStepTargets.returns(mdp)).merge(m));
    }

    static void row(String what, ParaResult.Stats st) {
        System.out.printf("    %-18s leaves %6d  merged %6d  departures %7d  maxLive %5d%n",
            what, st.terminalLeaves, st.merged, st.departures, st.maxLive);
    }

    static void row(String what, NStepTargets.Estimate e) {
        System.out.printf("    %-18s leaves %6d  merged %6d  departures %7d  maxLive %5d  value %.12f%n",
            what, e.leaves, e.merged, e.departures, e.maxLive, e.value);
    }

    static void unbiased(Check c, String what, TabularMdp mdp, int s, int n, double[] v, SelectionRule rule,
                         int reps, double exact) {
        double sum = 0, sq = 0, work = 0;
        for (int i = 0; i < reps; i++) {
            NStepTargets.Estimate e = target(mdp, s, n, v, rule, EXP, 1000 + i);
            sum += e.value; sq += e.value * e.value; work += e.departures;
        }
        double mean = sum / reps, var = (sq - reps * mean * mean) / (reps - 1), se = Math.sqrt(var / reps);
        System.out.printf("  %-34s mean %.5f (exact %.5f, se %.5f) variance %.2e work %.0f%n", what, mean, exact, se, var, work / reps);
        c.equal(what + ": mean within 4 s.e.", Math.abs(mean - exact) <= 4 * se + 1e-12, true);
    }

    static boolean throwsIAE(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { System.out.println("    refused: " + e.getMessage()); return true; }
    }

    static boolean throwsISE(Runnable r) {
        try { r.run(); return false; } catch (IllegalStateException e) { return true; }
    }
}
