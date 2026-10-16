package examples.rl;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;

/**
 * Does the paratemporal engine help n-step Monte Carlo expansion with k branches per node?
 * (build test "nstep-compare"; results in rl/experiments/results/java_nstep_compare.csv)
 *
 * For each problem, lookahead n and branching k, the n-step expected target from several start
 * states is computed by:
 * <ul>
 * <li>exhaustive, without and with safe merging (exact; how much work merging saves);</li>
 * <li>a k-branch tree (k samples per draw), without and with safe merging;</li>
 * <li>budget allocation B = k^2 with safe merging (merged nodes get samples in proportion to weight);</li>
 * <li>plain independent rollouts, N = k^2.</li>
 * </ul>
 * Bootstrapping uses V = V^pi, so every exact target equals V^pi(s) and the sampled estimators'
 * bias is measured against a known value. Work is the engine's departure count (model transitions
 * run, including replays), the same unit for every method. Efficiency is variance x work: for
 * unbiased estimators variance falls as 1/work, so var x work compares methods at equal compute
 * (lower is better).
 *
 * Replications run in parallel on -Dthreads worker threads (default: all cores). Each replication
 * keeps its own seed and the values are summed in replication order, so the results are identical
 * for any thread count.
 */
public class CompareNStep {

    static final int REPS = Integer.getInteger("reps", 200);
    static final ForkJoinPool POOL = new ForkJoinPool(Integer.getInteger("threads", Runtime.getRuntime().availableProcessors()));
    static final int[] NS = {2, 4, 6};
    static final int[] KS = {2, 3};

    record Row(String problem, int n, int k, String method, double bias, double se, double variance,
               double work, double efficiency) {}

    public static void main(String[] args) throws IOException {
        List<Row> rows = new ArrayList<>();

        TabularMdp rw = TabularMdp.randomWalk19();
        run(rows, "random walk 19", rw, rw.evaluatePolicy(), new int[] {4, 7, 10, 13, 16});

        TabularMdp fl = TabularMdp.frozenLake8x8(0.99).epsilonSoftOptimal(0.2);
        run(rows, "FrozenLake 8x8", fl, fl.evaluatePolicy(), new int[] {0, 10, 18, 33, 44, 57});

        Path out = Paths.get("rl/experiments/results/java_nstep_compare.csv");
        Files.createDirectories(out.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("problem,n,k,method,mean_abs_bias,mean_se,mean_variance,mean_work,efficiency_var_x_work");
            for (Row r : rows)
                w.printf("%s,%d,%d,%s,%.6g,%.6g,%.6g,%.1f,%.6g%n", r.problem, r.n, r.k, r.method, r.bias, r.se,
                    r.variance, r.work, r.efficiency);
        }
        System.out.println("wrote " + out);
    }

    static void run(List<Row> rows, String name, TabularMdp mdp, double[] v, int[] starts) {
        ParaSpec.Merge NONE = ParaSpec.Merge.NONE, SAFE = ParaSpec.Merge.SAFE;
        System.out.printf("%n== %s (%d start states, %d replications per sampled method)%n", name, starts.length, REPS);
        System.out.printf("%-3s %-2s %-26s %10s %10s %11s %10s %12s%n",
            "n", "k", "method", "|bias|", "s.e.", "variance", "work", "var x work");
        for (int n : NS) {
            // Exact methods do not depend on k: report them once per n.
            add(rows, name, n, 0, "exhaustive, no merge", mdp, v, starts, n, SelectionRule.exhaustive(), NONE, 1, 1);
            add(rows, name, n, 0, "exhaustive + merge", mdp, v, starts, n, SelectionRule.exhaustive(), SAFE, 1, 1);
            for (int k : KS) {
                add(rows, name, n, k, "k-tree, no merge", mdp, v, starts, n, SelectionRule.monteCarlo(k), NONE, 1, REPS);
                add(rows, name, n, k, "k-tree + merge", mdp, v, starts, n, SelectionRule.monteCarlo(k), SAFE, 1, REPS);
                add(rows, name, n, k, "budget B=k^2 + merge", mdp, v, starts, n, SelectionRule.budget(k * k), SAFE, 1, REPS);
                add(rows, name, n, k, "rollouts N=k^2", mdp, v, starts, n, SelectionRule.monteCarlo(), NONE, k * k, REPS);
            }
        }
    }

    /** Runs replications 0..reps-1 on the worker pool; element i is replication i. */
    static NStepTargets.Estimate[] replicate(int reps, java.util.function.IntFunction<NStepTargets.Estimate> one) {
        try {
            return POOL.submit(() -> IntStream.range(0, reps).parallel().mapToObj(one)
                .toArray(NStepTargets.Estimate[]::new)).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Replication failed", e);
        }
    }

    /** Runs one method from every start state and records the averages over start states. */
    static void add(List<Row> rows, String name, int n, int k, String method, TabularMdp mdp, double[] v,
                    int[] starts, int steps, SelectionRule rule, ParaSpec.Merge merge, int roots, int reps) {
        double bias = 0, se = 0, var = 0, work = 0, eff = 0;
        for (int s : starts) {
            double exact = v[s];
            NStepTargets.Estimate[] est = replicate(reps, i -> NStepTargets.target(mdp, s, steps, v, MdpWalker.Mode.JOINT,
                rule, merge, roots, 1_000_000L * s + 1000L * steps + i));
            double sum = 0, sq = 0, wsum = 0;
            for (NStepTargets.Estimate e : est) { sum += e.value; sq += e.value * e.value; wsum += e.departures; }
            double mean = sum / reps;
            double vs = reps > 1 ? Math.max(0, (sq - reps * mean * mean) / (reps - 1)) : 0;
            double ws = wsum / reps;
            bias += Math.abs(mean - exact); se += Math.sqrt(vs / reps); var += vs; work += ws; eff += vs * ws;
        }
        int m = starts.length;
        Row r = new Row(name, n, k, method, bias / m, se / m, var / m, work / m, eff / m);
        rows.add(r);
        System.out.printf("%-3d %-2s %-26s %10.2e %10.2e %11.3e %10.0f %12.3e%n",
            n, k == 0 ? "-" : String.valueOf(k), method, r.bias, r.se, r.variance, r.work, r.efficiency);
    }
}
