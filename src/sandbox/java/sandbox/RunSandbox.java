package sandbox;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;
import paradevs.engine.Transducer;

/**
 * Sandbox runner (VS Code launch config "Sandbox: RunSandbox"). Edit freely.
 *
 * Runs RandomWalk exhaustively, with safe merging, and by Monte Carlo, and compares the final
 * position with its exact binomial law.
 */
public class RunSandbox {

    /** xi = latest reported position. */
    static final Transducer<Integer> LAST_POSITION = new Transducer<Integer>() {
        @Override public Integer initial() { return 0; }
        @Override public Integer record(Integer xi, double t, List<OutputEvent> outs) {
            for (OutputEvent e : outs) if (e.port.equals("position")) xi = (Integer) e.value;
            return xi;
        }
    };

    public static void main(String[] args) {
        int n = 6;
        double p = 0.5;

        ParaSpec<Integer> base = ParaSpec.of(() -> new RandomWalk(n, p), LAST_POSITION);
        ParaResult<Integer> exact = ParaEngine.run(base);
        ParaResult<Integer> merged = ParaEngine.run(ParaSpec.of(() -> new RandomWalk(n, p), LAST_POSITION)
            .merge(ParaSpec.Merge.SAFE));
        ParaResult<Integer> mc = ParaEngine.run(ParaSpec.of(() -> new RandomWalk(n, p), LAST_POSITION)
            .selection(SelectionRule.monteCarlo()).roots(5000).seed(1));

        System.out.println("exhaustive : " + exact.stats());
        System.out.println("safe merge : " + merged.stats());
        System.out.println("monte carlo: " + mc.stats());
        System.out.println("final position   exact      merged     MC        binomial");
        Map<Integer, Double> a = new TreeMap<>(exact.marginal(x -> x));
        Map<Integer, Double> b = merged.marginal(x -> x), c = mc.marginal(x -> x);
        double worst = 0;
        for (Map.Entry<Integer, Double> e : a.entrySet()) {
            int k = (e.getKey() + n) / 2;   // number of up-steps
            double binom = choose(n, k) * Math.pow(p, k) * Math.pow(1 - p, n - k);
            worst = Math.max(worst, Math.abs(e.getValue() - binom));
            System.out.printf("  %+3d          %.6f   %.6f   %.4f    %.6f%n",
                e.getKey(), e.getValue(), b.getOrDefault(e.getKey(), 0.0), c.getOrDefault(e.getKey(), 0.0), binom);
        }
        System.out.println(worst < 1e-12 ? "OK: exhaustive result matches the binomial law" : "MISMATCH: " + worst);
    }

    static double choose(int n, int k) {
        double r = 1;
        for (int i = 1; i <= k; i++) r = r * (n - k + i) / i;
        return r;
    }
}
