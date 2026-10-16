package examples.rl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Future;

import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;

/**
 * Cost growth with the lookahead n (results in rl/experiments/results/cost_growth.csv and
 * cost_reach.csv).
 *
 * For n = 2..12 on FrozenLake 8x8 and the 19-state random walk (one draw per step, JOINT mode),
 * measures the work (departures) of exhaustive expansion without merging (capped by maxLive),
 * exhaustive expansion with safe merging, k-trees and budget allocation with merging, and plain
 * rollouts; on FrozenLake also the two-draw (SEPARATE) exhaustive expansion and the best hybrid of
 * {@link CompareHybrid}. Sampled methods also report variance, so variance x work gives the work
 * needed for any target precision.
 *
 * cost_reach.csv holds the exact level-by-level count of merged nodes (distinct live states
 * reachable in exactly t steps), the predicted merged exhaustive work computed from it, and the
 * reachable set, which bound the merged work by about n x |reachable| x (1 + branching).
 *
 * Resumable (rows in the CSV are skipped). Options: -Dn=2,...,12 -Dreps=100 -Dbudget=170 (seconds)
 * -Dproblem=fl|rw.
 */
public class CostGrowth {

    static final int REPS = Integer.getInteger("reps", 100);
    static final long MAX_DEP = Long.getLong("maxdep", 150_000L);
    static final long CAP = 50_000;
    static final Path OUT = Paths.get("rl/experiments/results/cost_growth.csv");
    static final Path REACH = Paths.get("rl/experiments/results/cost_reach.csv");
    static final String HEADER = "problem,n,method,draws,merge,reps,mean_abs_bias,max_abs_z,mean_variance,mean_work,"
        + "efficiency_var_x_work,status";
    static final long T0 = System.nanoTime();
    static final long BUDGET_S = Long.getLong("budget", 170);

    record Method(String name, MdpWalker.Mode mode, SelectionRule rule, ParaSpec.Merge merge, int roots, boolean exact,
                  boolean lagFree) {
        Method(String name, MdpWalker.Mode mode, SelectionRule rule, ParaSpec.Merge merge, int roots, boolean exact) {
            this(name, mode, rule, merge, roots, exact, false);
        }
    }

    public static void main(String[] args) throws IOException {
        int[] ns = HybridRuns.ints("n", new int[] {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        String only = System.getProperty("problem");
        TabularMdp fl = TabularMdp.frozenLake8x8(0.99).epsilonSoftOptimal(0.2);
        TabularMdp rw = TabularMdp.randomWalk19();
        int[] flStarts = {0, 10, 18, 33, 44, 57}, rwStarts = {4, 7, 10, 13, 16};
        if (!Files.exists(REACH)) {
            List<String> rows = new ArrayList<>();
            reach(rows, "FrozenLake 8x8", fl, flStarts);
            reach(rows, "random walk 19", rw, rwStarts);
            HybridRuns.append(REACH, "problem,start,n,live_states_at_n,live_pairs_at_n,predicted_work_lagged,"
                + "predicted_work_lagfree,reachable_live_states,sum_cost_over_reachable,reachable_edges", rows);
        }
        Set<String> done = HybridRuns.doneKeys(OUT, 3);
        if (only == null || only.equals("fl")) run("FrozenLake 8x8", fl, fl.evaluatePolicy(), flStarts, ns, true, done);
        if (only == null || only.equals("rw")) run("random walk 19", rw, rw.evaluatePolicy(), rwStarts, ns, false, done);
        System.out.println("wrote " + OUT);
    }

    static void run(String name, TabularMdp mdp, double[] v, int[] starts, int[] ns, boolean twoDraws, Set<String> done)
            throws IOException {
        MdpWalker.Mode J = MdpWalker.Mode.JOINT, S = MdpWalker.Mode.SEPARATE;
        List<Method> ms = new ArrayList<>();
        ms.add(new Method("exhaustive no merge", J, SelectionRule.exhaustive(), HybridRuns.NONE, 1, true));
        ms.add(new Method("exhaustive + merge", J, SelectionRule.exhaustive(), HybridRuns.SAFE, 1, true));
        if (twoDraws) ms.add(new Method("exhaustive + merge (two draws)", S, SelectionRule.exhaustive(), HybridRuns.SAFE, 1, true));
        ms.add(new Method("exhaustive + merge (lag-free key)", J, SelectionRule.exhaustive(), HybridRuns.SAFE, 1, true, true));
        ms.add(new Method("k-tree k=2 + merge", J, SelectionRule.monteCarlo(2), HybridRuns.SAFE, 1, false));
        ms.add(new Method("k-tree k=3 + merge", J, SelectionRule.monteCarlo(3), HybridRuns.SAFE, 1, false));
        ms.add(new Method("budget B=4 + merge", J, SelectionRule.budget(4), HybridRuns.SAFE, 1, false));
        ms.add(new Method("budget B=9 + merge", J, SelectionRule.budget(9), HybridRuns.SAFE, 1, false));
        ms.add(new Method("budget B=9 + merge (lag-free key)", J, SelectionRule.budget(9), HybridRuns.SAFE, 1, false, true));
        ms.add(new Method("rollouts N=9", J, SelectionRule.monteCarlo(), HybridRuns.NONE, 9, false));
        if (twoDraws) ms.add(new Method("hybrid budget action B=9 / enum slip + merge", S,
            HybridRuns.perDraw(SelectionRule.budget(9), SelectionRule.exhaustive()), HybridRuns.SAFE, 1, false));
        int mi = 0;
        for (Method m : ms) {
            mi++;
            boolean capped = false;
            for (int n : ns) {
                String key = "\"" + name + "\"," + n + ",\"" + m.name + "\"";
                if (done.contains(key)) { capped |= rowCapped(key); continue; }
                if ((System.nanoTime() - T0) / 1e9 > BUDGET_S) { System.out.println("time budget reached; rerun to resume"); System.exit(0); }
                String row = capped ? capRow(m) : measure(mdp, v, starts, n, mi, m);
                capped |= row.contains("exceeds maxLive");
                HybridRuns.append(OUT, HEADER, List.of(key + "," + row));
                System.out.printf("%-15s n=%-2d %-44s %s%n", name, n, m.name, row.substring(row.indexOf(',', row.indexOf(',') + 1) + 1));
            }
        }
    }

    static boolean rowCapped(String key) throws IOException {
        for (String line : Files.readAllLines(OUT)) if (line.startsWith(key + ",") && line.contains("exceeds maxLive")) return true;
        return false;
    }

    static String capRow(Method m) {
        return String.format("%s,%s,0,,,,,,\"exceeds maxLive %d\"", m.mode == MdpWalker.Mode.JOINT ? "one" : "two", m.merge, CAP);
    }

    static String measure(TabularMdp mdp, double[] v, int[] starts, int n, int mi, Method m) {
        double bias = 0, maxz = 0, var = 0, work = 0, eff = 0;
        int minReps = Integer.MAX_VALUE;
        String draws = m.mode == MdpWalker.Mode.JOINT ? "one" : "two";
        long cap = m.merge == HybridRuns.NONE && m.exact ? CAP : 400_000;
        List<HybridRuns.Stat> stats = new ArrayList<>();
        if (m.exact) {
            if (m.merge == HybridRuns.NONE) {          // one at a time: the unmerged live set is large
                for (int s : starts) stats.add(HybridRuns.replicate(mdp, s, n, v, m.mode, m.rule, m.merge, 1, 1, 1, cap, m.lagFree));
            } else {
                List<Future<HybridRuns.Stat>> fs = new ArrayList<>();
                for (int s : starts) fs.add(HybridRuns.POOL.submit(() -> {
                    double[] x = HybridRuns.once(mdp, s, n, v, m.mode, m.rule, m.merge, 1, 1, cap, m.lagFree);
                    HybridRuns.Stat st = new HybridRuns.Stat(); st.reps = 1; st.mean = x[0]; st.work = x[1]; return st;
                }));
                try { for (Future<HybridRuns.Stat> f : fs) stats.add(f.get()); } catch (Exception e) { throw new RuntimeException(e); }
            }
        } else {
            for (int s : starts) {
                long seed = 1_000_000_000L * mi + 1_000_000L * s + 10_000L * n;
                HybridRuns.Stat pilot = HybridRuns.replicate(mdp, s, n, v, m.mode, m.rule, m.merge, m.roots, 1, seed + 9999, cap, m.lagFree);
                int reps = (int) Math.min(REPS, Math.max(30, MAX_DEP / Math.max(1, pilot.work)));
                stats.add(HybridRuns.replicate(mdp, s, n, v, m.mode, m.rule, m.merge, m.roots, reps, seed, cap, m.lagFree));
            }
        }
        for (int i = 0; i < starts.length; i++) {
            HybridRuns.Stat st = stats.get(i);
            if (st.work < 0) return String.format("%s,%s,0,,,,,,\"exceeds maxLive %d\"", draws, m.merge, cap);
            double b = Math.abs(st.mean - v[starts[i]]);
            minReps = Math.min(minReps, st.reps);
            bias += b; var += st.var; work += st.work; eff += st.var * st.work;
            if (st.se() > 0) maxz = Math.max(maxz, b / st.se());
        }
        int k = starts.length;
        return String.format("%s,%s,%d,%.6g,%.4g,%.6g,%.1f,%.6g,\"ok\"", draws, m.merge, minReps, bias / k, maxz,
            var / k, work / k, eff / k);
    }

    // ------------------------------------------------------------------ reachable sets and predicted work

    /** Departures to expand one merged node in state s: 1 if its draw is degenerate, else 1 + #outcomes. */
    static int cost(TabularMdp mdp, int s) {
        int b = mdp.stepLaw(s).atoms().size();
        return b > 1 ? 1 + b : 1;
    }

    /**
     * Exact merged exhaustive work, level by level. L_t = distinct live (non-terminal) states
     * reachable in exactly t steps. Live returns are all 0 here (rewards come only on terminal
     * arrival), so merged nodes differ only in state, except for the reporting lag:
     * <ul>
     * <li>{@link MdpWalker} (lagged): right after a step the observation still holds the previous
     *   state, so the nodes at level t+1 are the pairs (s', s) with s in L_t and s' a successor of s.
     *   W = 1 + cost(s0) + sum_{t=1..n-1} sum_{live pairs at t} cost(s') + #terminal pairs + #live pairs at n.</li>
     * <li>{@link LagFreeWalker}: the observation does not hold the state, so nodes merge on the state.
     *   W = 1 + sum_{t<n} sum_{s in L_t} cost(s) + sum_{t=1..n} |T_t| + |L_n|, T_t the distinct
     *   terminal states reached at step t.</li>
     * </ul>
     * Per level the lag-free work is at most sum_{s in R} cost(s), about |R| x (1 + b), over the
     * reachable live set R; the lagged work is about |E| x (1 + b) over the reachable edges E.
     */
    static void reach(List<String> rows, String name, TabularMdp mdp, int[] starts) {
        for (int s0 : starts) {
            Set<Integer> reachable = new TreeSet<>(List.of(s0));
            Set<Integer> live = new TreeSet<>(List.of(s0));
            long lagged = 1 + cost(mdp, s0), lagTerm = 0, free = 1, freeTerm = 0;
            for (int n = 1; n <= 24; n++) {   // beyond the measured n = 12, to show saturation
                Set<Integer> next = new TreeSet<>();
                long livePairs = 0, termPairs = 0, livePairCost = 0;
                Set<Integer> term = new TreeSet<>();
                for (int s : live) {
                    free += cost(mdp, s);
                    for (var a : mdp.stepLaw(s).atoms()) {
                        int s2 = (Integer) a.value;
                        if (mdp.isTerminal(s2)) { termPairs++; term.add(s2); }
                        else { livePairs++; livePairCost += cost(mdp, s2); next.add(s2); }
                    }
                }
                freeTerm += term.size();
                lagTerm += termPairs;
                long lagW = lagged + lagTerm + livePairs;   // the level-n live pairs only report
                lagged += livePairCost;                      // ... or expand, if the walk continues
                live = next;
                long freeW = free + freeTerm + live.size();
                reachable.addAll(next);
                long sumCost = 0, edges = 0;
                for (int s : reachable) {
                    sumCost += cost(mdp, s);
                    for (var a : mdp.stepLaw(s).atoms()) edges++;
                }
                rows.add(String.format("\"%s\",%d,%d,%d,%d,%d,%d,%d,%d,%d", name, s0, n, live.size(), livePairs,
                    lagW, freeW, reachable.size(), sumCost, edges));
            }
        }
    }
}
