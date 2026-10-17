package examples.rl;

import examples.Check;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;

/**
 * Checks for the hybrid-selection study (build test "hybrid").
 *
 * <ul>
 * <li>Enumerating both draws through SelectionRule.hybrid gives the exact target V^pi(s).</li>
 * <li>The sampled hybrids (sample one draw, enumerate the other; budget on one draw) are unbiased:
 *   the mean over independent seeds lies within 4 standard errors of the exact target.</li>
 * <li>{@link LagFreeWalker} gives the same exact targets with less merged work, and its work matches
 *   the level-by-level prediction of {@link CostGrowth}.</li>
 * <li>The exact law-of-total-variance split of a rollout adds up to the observed rollout variance.</li>
 * </ul>
 */
public class VerifyHybrid {

    public static void main(String[] args) {
        Check c = new Check("hybrid selection (FrozenLake 8x8, two draws per step)");
        ParaSpec.Merge NONE = HybridRuns.NONE, SAFE = HybridRuns.SAFE;
        MdpWalker.Mode SEP = MdpWalker.Mode.SEPARATE, JOINT = MdpWalker.Mode.JOINT;
        SelectionRule ex = SelectionRule.exhaustive();

        TabularMdp fl = TabularMdp.frozenLake8x8(0.99).epsilonSoftOptimal(0.2);
        double[] v = fl.evaluatePolicy();
        int s0 = 18, n = 3;
        double[] e = HybridRuns.once(fl, s0, n, v, SEP, HybridRuns.perDraw(ex, ex), SAFE, 1, 1, 400_000);
        c.close("hybrid(enum, enum) + merge = V(18)", e[0], v[s0], 1e-12);

        int reps = 300;
        unbiased(c, "sample action k=2, enumerate slip, merge", fl, s0, n, v,
            HybridRuns.perDraw(SelectionRule.monteCarlo(2), ex), SAFE, reps);
        unbiased(c, "enumerate action, sample slip k=2, merge", fl, s0, n, v,
            HybridRuns.perDraw(ex, SelectionRule.monteCarlo(2)), SAFE, reps);
        unbiased(c, "budget action B=4, enumerate slip, merge", fl, s0, n, v,
            HybridRuns.perDraw(SelectionRule.budget(4), ex), SAFE, reps);
        unbiased(c, "sample action k=2, enumerate slip, no merge", fl, s0, n, v,
            HybridRuns.perDraw(SelectionRule.monteCarlo(2), ex), NONE, reps);

        // Lag-free observation: same exact value, less work, and the predicted work.
        TabularMdp rw = TabularMdp.randomWalk19();
        double[] vrw = rw.evaluatePolicy();
        double[] std = HybridRuns.once(fl, s0, 6, v, JOINT, ex, SAFE, 1, 1, 400_000, false);
        double[] free = HybridRuns.once(fl, s0, 6, v, JOINT, ex, SAFE, 1, 1, 400_000, true);
        c.close("lag-free key, FL n=6 = V(18)", free[0], v[s0], 1e-12);
        c.equal("lag-free key merges more (FL n=6)", free[1] < std[1], true);
        double[] rwFree = HybridRuns.once(rw, 13, 8, vrw, JOINT, ex, SAFE, 1, 1, 400_000, true);
        c.close("lag-free key, RW n=8 = V(13)", rwFree[0], vrw[13], 1e-12);
        java.util.List<String> rows = new java.util.ArrayList<>();
        CostGrowth.reach(rows, "fl", fl, new int[] {s0});
        String[] row6 = rows.get(5).split(",");     // n = 6
        c.equal("predicted lagged work (FL n=6)", Long.parseLong(row6[5]), (long) std[1]);
        c.equal("predicted lag-free work (FL n=6)", Long.parseLong(row6[6]), (long) free[1]);

        // Variance split: policy part + slip part = rollout variance (n = 2, start 18).
        double[] pv = CompareHybrid.split(fl, v, s0, 2);
        HybridRuns.Stat ro = HybridRuns.replicate(fl, s0, 2, v, SEP, SelectionRule.monteCarlo(), NONE, 1, 6000, 555_000, 1000);
        System.out.printf("  split n=2: policy %.3e + slip %.3e = %.3e; observed rollout variance %.3e%n",
            pv[0], pv[1], pv[0] + pv[1], ro.var);
        c.equal("variance split adds up to the rollout variance (within 10%)",
            Math.abs(ro.var - (pv[0] + pv[1])) <= 0.10 * (pv[0] + pv[1]), true);
        c.equal("slip draw carries most of the variance", pv[1] > pv[0], true);
        c.finish();
        HybridRuns.POOL.shutdown();
    }

    static void unbiased(Check c, String what, TabularMdp mdp, int s, int n, double[] v, SelectionRule rule,
                         ParaSpec.Merge merge, int reps) {
        HybridRuns.Stat st = HybridRuns.replicate(mdp, s, n, v, MdpWalker.Mode.SEPARATE, rule, merge, 1, reps, 4242, 400_000);
        System.out.printf("  %-44s mean %.5f (exact %.5f, se %.5f) work %.0f%n", what, st.mean, v[s], st.se(), st.work);
        c.equal(what + ": mean within 4 s.e.", Math.abs(st.mean - v[s]) <= 4 * st.se() + 1e-12, true);
    }
}
