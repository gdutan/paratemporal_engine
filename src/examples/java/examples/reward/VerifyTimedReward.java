package examples.reward;

import java.util.LinkedHashMap;
import java.util.Map;

import examples.Check;
import paradevs.engine.InputTrace;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;
import paradevs.engine.Transducer;
import paradevs.engine.Transducer.History;

/**
 * Acceptance test 1: the timed reward example of main_paratemporal against the exact rational
 * results of pt_reward_enumeration.py (Do s/documents/paratemporal).
 *
 * Expected (psλ, exhaustive, no merging): 64 leaves, 63 random choice nodes, and emitted reward
 * pairs (r at 3/2, r at 5/2) with probabilities (6352, 10948, 9793, 12907) / 40000.
 */
public class VerifyTimedReward {

    static ParaSpec<History> spec() {
        InputTrace trace = InputTrace.builder()
            .at(0.0, "action", "a").at(0.5, "action", "b").at(1.5, "action", "c").build();
        return ParaSpec.of(TimedReward::new, Transducer.history()).trace(trace).horizon(5);
    }

    static String pair(History h) {
        StringBuilder s = new StringBuilder();
        for (History.Entry e : h.entries) s.append(e.time).append(":").append(e.output.value).append(" ");
        return s.toString().trim();
    }

    public static void main(String[] args) {
        Map<String, Double> exact = new LinkedHashMap<>();
        exact.put("1.5:0 2.5:0", 6352 / 40000.0);
        exact.put("1.5:0 2.5:1", 10948 / 40000.0);
        exact.put("1.5:1 2.5:0", 9793 / 40000.0);
        exact.put("1.5:1 2.5:1", 12907 / 40000.0);
        Check c = new Check("Timed reward (main_paratemporal worked example)");

        // 1. Exhaustive, no merging: exact law and exact tree size.
        ParaResult<History> ex = ParaEngine.run(spec());
        System.out.println("exhaustive, no merge : " + ex.stats());
        Map<String, Double> law = ex.marginal(VerifyTimedReward::pair);
        for (Map.Entry<String, Double> e : exact.entrySet()) {
            double got = law.getOrDefault(e.getKey(), 0.0);
            System.out.printf("  %-12s exact=%.10f engine=%.10f%n", e.getKey(), e.getValue(), got);
            c.close("P(" + e.getKey() + ")", got, e.getValue(), 1e-12);
        }
        c.close("total mass", ex.totalMass(), 1.0, 1e-12);
        c.equal("unmerged leaves", ex.stats().terminalLeaves, 64);
        c.equal("random choice nodes", ex.stats().choiceNodes, 63);
        c.equal("all leaves stop at horizon", ex.distribution().keySet().stream().allMatch(o -> o.status.name().equals("HORIZON")), true);

        // 2. Safe merging: the same law with fewer live branches.
        ParaResult<History> mg = ParaEngine.run(spec().merge(ParaSpec.Merge.SAFE));
        System.out.println("exhaustive, safe merge: " + mg.stats());
        Map<String, Double> mlaw = mg.marginal(VerifyTimedReward::pair);
        for (Map.Entry<String, Double> e : exact.entrySet())
            c.close("merged P(" + e.getKey() + ")", mlaw.getOrDefault(e.getKey(), 0.0), e.getValue(), 1e-12);
        c.equal("merging reduced choice nodes", mg.stats().choiceNodes < ex.stats().choiceNodes, true);

        // 3. Monte Carlo with N roots of weight 1/N: unbiased, so within a few standard errors.
        int n = 4000;
        ParaResult<History> mc = ParaEngine.run(spec().selection(SelectionRule.monteCarlo()).roots(n).seed(7));
        System.out.println("monte carlo N=" + n + "  : " + mc.stats());
        Map<String, Double> claw = mc.marginal(VerifyTimedReward::pair);
        for (Map.Entry<String, Double> e : exact.entrySet()) {
            double p = e.getValue(), se = Math.sqrt(p * (1 - p) / n);
            c.close("MC P(" + e.getKey() + ") within 4 s.e.", claw.getOrDefault(e.getKey(), 0.0), p, 4 * se);
        }
        c.close("MC total mass", mc.totalMass(), 1.0, 1e-9);
        boolean refused = false;
        try { ParaEngine.run(spec().selection(SelectionRule.monteCarlo()).roots(10).merge(ParaSpec.Merge.SAFE)); }
        catch (IllegalArgumentException e) { refused = true; }
        c.equal("MC roots + safe merge is refused", refused, true);

        // 4. Partial exhaustive: residual bounds every event probability.
        ParaResult<History> pt = ParaEngine.run(spec().selection(SelectionRule.partialExhaustive(0.3)));
        System.out.println("partial (p>=0.3)     : " + pt.stats() + " residual=" + pt.residual());
        c.close("partial mass + residual = 1", pt.totalMass() + pt.residual(), 1.0, 1e-12);
        Map<String, Double> plaw = pt.marginal(VerifyTimedReward::pair);
        for (Map.Entry<String, Double> e : exact.entrySet()) {
            double lo = plaw.getOrDefault(e.getKey(), 0.0);
            c.equal("bounds hold for " + e.getKey(), lo <= e.getValue() + 1e-12 && e.getValue() <= lo + pt.residual() + 1e-12, true);
        }

        c.finish();
    }
}
