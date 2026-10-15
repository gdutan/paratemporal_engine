package examples.rl;

import examples.Check;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;

/**
 * Checks for n-step targets under the engine (build test "nstep").
 *
 * With V = V^pi the expected n-step target equals V^pi(s) for every n (Bellman consistency), so
 * the exhaustive targets have a known exact answer, with or without merging. The sampled
 * estimators (k branches per draw, budget, plain rollouts) must be unbiased: their mean over
 * independent seeds lies within 4 standard errors of the exact target.
 */
public class VerifyNStep {

    public static void main(String[] args) {
        Check c = new Check("n-step targets (random walk, FrozenLake 8x8)");
        ParaSpec.Merge NONE = ParaSpec.Merge.NONE, SAFE = ParaSpec.Merge.SAFE;
        MdpWalker.Mode JOINT = MdpWalker.Mode.JOINT, SEP = MdpWalker.Mode.SEPARATE;

        // Random walk: V(s) = (s - 10) / 10.
        TabularMdp rw = TabularMdp.randomWalk19();
        double[] vrw = rw.evaluatePolicy();
        c.close("random walk V(13) = 0.3", vrw[13], 0.3, 1e-9);
        for (int n : new int[] {1, 4, 8}) {
            c.close("RW exhaustive n=" + n + " = V(13)",
                NStepTargets.target(rw, 13, n, vrw, JOINT, SelectionRule.exhaustive(), NONE, 1, 1).value, vrw[13], 1e-12);
            c.close("RW exhaustive+merge n=" + n + " = V(13)",
                NStepTargets.target(rw, 13, n, vrw, JOINT, SelectionRule.exhaustive(), SAFE, 1, 1).value, vrw[13], 1e-12);
        }

        // FrozenLake 8x8, gamma 0.99, epsilon-soft optimal policy (eps = 0.2), as in rl/experiments/t2.
        TabularMdp fl = TabularMdp.frozenLake8x8(0.99);
        c.close("FrozenLake V*(start) = Gymnasium-derived Python value", fl.valueIteration()[0], 0.41464036178745123, 1e-9);
        fl.epsilonSoftOptimal(0.2);
        double[] vfl = fl.evaluatePolicy();
        int s0 = 18;
        for (int n : new int[] {1, 2, 3}) {
            double want = vfl[s0];
            c.close("FL exhaustive n=" + n + " = V(18)",
                NStepTargets.target(fl, s0, n, vfl, JOINT, SelectionRule.exhaustive(), NONE, 1, 1).value, want, 1e-12);
            c.close("FL exhaustive+merge n=" + n + " = V(18)",
                NStepTargets.target(fl, s0, n, vfl, JOINT, SelectionRule.exhaustive(), SAFE, 1, 1).value, want, 1e-12);
            c.close("FL separate draws, exhaustive+merge n=" + n + " = V(18)",
                NStepTargets.target(fl, s0, n, vfl, SEP, SelectionRule.exhaustive(), SAFE, 1, 1).value, want, 1e-12);
        }
        NStepTargets.Estimate a = NStepTargets.target(fl, s0, 3, vfl, JOINT, SelectionRule.exhaustive(), NONE, 1, 1);
        NStepTargets.Estimate b = NStepTargets.target(fl, s0, 3, vfl, JOINT, SelectionRule.exhaustive(), SAFE, 1, 1);
        System.out.printf("  FL n=3 exhaustive: departures %d without merge, %d with merge%n", a.departures, b.departures);
        c.equal("merging reduces work", b.departures < a.departures, true);

        // Unbiasedness of the sampled estimators (FrozenLake, n = 4).
        int n = 4, reps = 400;
        double exact = vfl[s0];
        unbiased(c, "k=3 tree, no merge", fl, s0, n, vfl, SelectionRule.monteCarlo(3), NONE, 1, reps, exact);
        unbiased(c, "k=3 tree, safe merge", fl, s0, n, vfl, SelectionRule.monteCarlo(3), SAFE, 1, reps, exact);
        unbiased(c, "budget B=20, safe merge", fl, s0, n, vfl, SelectionRule.budget(20), SAFE, 1, reps, exact);
        unbiased(c, "20 plain rollouts", fl, s0, n, vfl, SelectionRule.monteCarlo(), NONE, 20, reps, exact);
        c.finish();
    }

    static void unbiased(Check c, String what, TabularMdp mdp, int s, int n, double[] v, SelectionRule rule,
                         ParaSpec.Merge merge, int roots, int reps, double exact) {
        double sum = 0, sq = 0, work = 0;
        for (int i = 0; i < reps; i++) {
            NStepTargets.Estimate e = NStepTargets.target(mdp, s, n, v, MdpWalker.Mode.JOINT, rule, merge, roots, 1000 + i);
            sum += e.value; sq += e.value * e.value; work += e.departures;
        }
        double mean = sum / reps, var = (sq - reps * mean * mean) / (reps - 1), se = Math.sqrt(var / reps);
        System.out.printf("  %-26s mean %.5f (exact %.5f, se %.5f) variance %.2e work %.0f%n", what, mean, exact, se, var, work / reps);
        c.equal(what + ": mean within 4 s.e.", Math.abs(mean - exact) <= 4 * se + 1e-12, true);
    }
}
