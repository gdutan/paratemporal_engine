package examples.rl;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;

/**
 * Hybrid selection study (results in rl/experiments/results/hybrid_study.csv and
 * hybrid_variance_split.csv).
 *
 * FrozenLake 8x8 (gamma 0.99, epsilon-soft optimal policy, eps 0.2) in {@link MdpWalker.Mode#SEPARATE}:
 * every step makes two draws, "action" (policy, 4 outcomes) and "transition" (slip, 3 outcomes).
 * Each draw gets its own rule through SelectionRule.hybrid: enumerate it, sample k values, or
 * sample with budget allocation B = k^2. The n-step expected target with V = V^pi has the exact
 * value V^pi(s), so bias is measured against a known answer.
 *
 * Also writes the exact law-of-total-variance split of a single rollout's n-step target into the
 * part due to the policy draw and the part due to the slip draw (computed by forward dynamic
 * programming, no sampling), plus the observed rollout variance as a check.
 *
 * Resumable: rows already in the CSV are skipped. Options: -Dn=2,4,6 -Dk=2,3 -Dreps=200
 * -Dmaxdep=300000 (departures per start state and configuration; caps the replication count),
 * -Dmerge=NONE|SAFE (run only one merge mode), -Dbudget=180 (seconds; stop and resume later).
 */
public class CompareHybrid {

    static final int REPS = Integer.getInteger("reps", 200);
    static final int MIN_REPS = 30;
    static final long MAX_DEP = Long.getLong("maxdep", 300_000L);
    static final long MAX_WORK_PER_RUN = 25_000;           // sampled configurations above this are reported, not replicated
    static final long CAP = 30_000;                        // maxLive for unmerged runs
    static final int[] STARTS = {0, 10, 18, 33, 44, 57};
    static final Path OUT = Paths.get("rl/experiments/results/hybrid_study.csv");
    static final Path SPLIT = Paths.get("rl/experiments/results/hybrid_variance_split.csv");
    static final String HEADER = "n,k,config,merge,draws,action_rule,slip_rule,reps,mean_abs_bias,max_abs_z,"
        + "mean_se,mean_variance,mean_work,efficiency_var_x_work,status";

    record Config(String name, MdpWalker.Mode mode, String actionRule, String slipRule, SelectionRule rule,
                  ParaSpec.Merge merge, int roots) {}

    public static void main(String[] args) throws IOException {
        TabularMdp fl = TabularMdp.frozenLake8x8(0.99).epsilonSoftOptimal(0.2);
        double[] v = fl.evaluatePolicy();
        int[] ns = HybridRuns.ints("n", new int[] {2, 4, 6});
        int[] ks = HybridRuns.ints("k", new int[] {2, 3});

        if (!Boolean.getBoolean("skipSplit") && !java.nio.file.Files.exists(SPLIT)) varianceSplit(fl, v);

        Set<String> done = HybridRuns.doneKeys(OUT, 4);
        System.out.printf("%-3s %-2s %-28s %-5s %5s %10s %6s %11s %10s %12s%n",
            "n", "k", "config", "merge", "reps", "|bias|", "max|z|", "variance", "work", "var x work");
        for (int n : ns) {
            List<Config> exact = List.of(
                cfg("enum both", "enum", "enum", SelectionRule.exhaustive(), HybridRuns.NONE),
                cfg("enum both", "enum", "enum", SelectionRule.exhaustive(), HybridRuns.SAFE));
            runAll(fl, v, n, 0, exact, done);
            for (int k : ks) {
                SelectionRule mc = SelectionRule.monteCarlo(k), ex = SelectionRule.exhaustive(), bu = SelectionRule.budget(k * k);
                String sk = "k=" + k, sb = "B=" + (k * k);
                List<Config> cs = new ArrayList<>();
                for (ParaSpec.Merge m : new ParaSpec.Merge[] {HybridRuns.NONE, HybridRuns.SAFE}) {
                    cs.add(cfg("sample action / enum slip", sk, "enum", HybridRuns.perDraw(mc, ex), m));
                    cs.add(cfg("enum action / sample slip", "enum", sk, HybridRuns.perDraw(ex, mc), m));
                    cs.add(cfg("sample both", sk, sk, HybridRuns.perDraw(mc, mc), m));
                }
                cs.add(cfg("budget action / enum slip", sb, "enum", HybridRuns.perDraw(bu, ex), HybridRuns.SAFE));
                cs.add(cfg("enum action / budget slip", "enum", sb, HybridRuns.perDraw(ex, bu), HybridRuns.SAFE));
                cs.add(cfg("budget both", sb, sb, HybridRuns.perDraw(bu, bu), HybridRuns.SAFE));
                cs.add(new Config("joint draw k-tree", MdpWalker.Mode.JOINT, sk, "(joint)", mc, HybridRuns.SAFE, 1));
                cs.add(new Config("joint draw budget", MdpWalker.Mode.JOINT, sb, "(joint)", bu, HybridRuns.SAFE, 1));
                cs.add(new Config("rollouts N=k^2", MdpWalker.Mode.SEPARATE, "k=1", "k=1", SelectionRule.monteCarlo(),
                    HybridRuns.NONE, k * k));
                runAll(fl, v, n, k, cs, done);
            }
        }
        System.out.println("wrote " + OUT);
    }

    static Config cfg(String name, String a, String s, SelectionRule r, ParaSpec.Merge m) {
        return new Config(name, MdpWalker.Mode.SEPARATE, a, s, r, m, 1);
    }

    static void runAll(TabularMdp mdp, double[] v, int n, int k, List<Config> cs, Set<String> done) throws IOException {
        int ci = 0;
        for (Config c : cs) {
            ci++;
            String key = n + "," + k + ",\"" + c.name + "\"," + c.merge;
            if (done.contains(key)) continue;
            if (ONLY != null && !ONLY.equals(c.merge.name())) continue;
            if ((System.nanoTime() - T0) / 1e9 > BUDGET_S) {
                System.out.println("time budget reached; rerun to resume");
                System.exit(0);
            }
            HybridRuns.append(OUT, HEADER, List.of(key + "," + measure(mdp, v, n, k, ci, c)));   // save every row
        }
    }

    static final long T0 = System.nanoTime();
    static final long BUDGET_S = Long.getLong("budget", 180);
    static final String ONLY = System.getProperty("merge");

    /** Average over start states of bias, standard error, variance, work and variance x work. */
    static String measure(TabularMdp mdp, double[] v, int n, int k, int ci, Config c) {
        boolean exact = k == 0;
        double bias = 0, maxz = 0, se = 0, var = 0, work = 0, eff = 0;
        int minReps = Integer.MAX_VALUE;
        String status = "ok";
        for (int s : STARTS) {
            long seed = 1_000_000_000L * ci + 1_000_000L * s + 10_000L * n + 1000L * k;
            long cap = c.merge == HybridRuns.NONE ? CAP : 400_000;
            HybridRuns.Stat pilot = HybridRuns.replicate(mdp, s, n, v, c.mode, c.rule, c.merge, c.roots, 1, seed + 999, cap);
            if (pilot.work < 0) { status = "exceeds maxLive " + cap; break; }
            int reps = exact ? 1 : (int) Math.min(REPS, Math.max(MIN_REPS, MAX_DEP / Math.max(1, pilot.work)));
            if (!exact && pilot.work > MAX_WORK_PER_RUN) { status = "too costly (work " + (long) pilot.work + " per run)"; work = pilot.work; break; }
            HybridRuns.Stat st = exact ? pilot : HybridRuns.replicate(mdp, s, n, v, c.mode, c.rule, c.merge, c.roots, reps, seed, cap);
            if (st.work < 0) { status = "exceeds maxLive " + cap; break; }
            minReps = Math.min(minReps, st.reps);
            double b = Math.abs(st.mean - v[s]);
            bias += b; se += st.se(); var += st.var; work += st.work; eff += st.var * st.work;
            if (st.se() > 0) maxz = Math.max(maxz, b / st.se());
        }
        int m = STARTS.length;
        if (!status.equals("ok")) {
            System.out.printf("%-3d %-2s %-28s %-5s   %s%n", n, k == 0 ? "-" : "" + k, c.name, c.merge, status);
            return String.format("%s,%s,%s,0,,,,,%s,,\"%s\"", c.mode == MdpWalker.Mode.JOINT ? "joint" : "separate",
                c.actionRule, c.slipRule, work > 0 ? String.format("%.1f", work) : "", status);
        }
        System.out.printf("%-3d %-2s %-28s %-5s %5d %10.2e %6.2f %11.3e %10.0f %12.3e%n",
            n, k == 0 ? "-" : "" + k, c.name, c.merge, minReps, bias / m, maxz, var / m, work / m, eff / m);
        return String.format("%s,%s,%s,%d,%.6g,%.4g,%.6g,%.6g,%.1f,%.6g,\"%s\"",
            c.mode == MdpWalker.Mode.JOINT ? "joint" : "separate", c.actionRule, c.slipRule, minReps,
            bias / m, maxz, se / m, var / m, work / m, eff / m, status);
    }

    // ------------------------------------------------------------------ law of total variance

    /**
     * For one rollout (one value per draw), the n-step target with V = V^pi is
     * V(s0) + sum_t gamma^t D_t with martingale increments D_t = R_{t+1} + gamma V(S_{t+1}) - V(S_t),
     * so Var = sum_t gamma^{2t} E[Var(Y_t | S_t)], Y_t = R_{t+1} + gamma V(S_{t+1}). Each conditional
     * variance splits (law of total variance over the action A) into
     *   policy part  Var_A( E[Y | s, A] )   and   slip part  E_A[ Var(Y | s, A) ].
     * The expectation over S_t uses the exact distribution of the walk (killed at terminal states).
     */
    static void varianceSplit(TabularMdp mdp, double[] v) throws IOException {
        List<String> rows = new ArrayList<>();
        int reps = Integer.getInteger("splitReps", 4000);
        for (int n : new int[] {1, 2, 4, 6}) {
            for (int s0 : STARTS) {
                double[] pv = split(mdp, v, s0, n);
                double vp = pv[0], vs = pv[1];
                HybridRuns.Stat ro = HybridRuns.replicate(mdp, s0, n, v, MdpWalker.Mode.SEPARATE, SelectionRule.monteCarlo(),
                    HybridRuns.NONE, 1, reps, 77_000_000L + 1000L * s0 + n, 1000);
                rows.add(String.format("%d,%d,%.6g,%.6g,%.6g,%.4f,%.6g,%.6g,%d", n, s0, vp, vs, vp + vs,
                    vp + vs > 0 ? vp / (vp + vs) : 0, ro.var, ro.var > 0 ? Math.sqrt(2.0 / (reps - 1)) * ro.var : 0, reps));
                System.out.printf("split n=%d s=%2d  policy %.3e  slip %.3e  total %.3e (policy share %.2f)  rollout var %.3e%n",
                    n, s0, vp, vs, vp + vs, vp + vs > 0 ? vp / (vp + vs) : 0, ro.var);
            }
        }
        java.nio.file.Files.deleteIfExists(SPLIT);
        HybridRuns.append(SPLIT, "n,start,policy_var,slip_var,total_var,policy_share,rollout_var_observed,rollout_var_se,reps", rows);
    }

    /** {policy part, slip part} of the variance of one rollout's n-step target from s0 (exact). */
    static double[] split(TabularMdp mdp, double[] v, int s0, int n) {
        int nS = mdp.nS, nA = mdp.nA;
        double[] pol = new double[nS], slip = new double[nS];
        for (int s = 0; s < nS; s++) {
            if (mdp.isTerminal(s)) continue;
            double[] qa = new double[nA], va = new double[nA];
            double mean = 0;
            for (int a = 0; a < nA; a++) {
                double m1 = 0, m2 = 0;
                for (int s2 = 0; s2 < nS; s2++) {
                    double p = mdp.p[s][a][s2];
                    if (p == 0) continue;
                    double y = mdp.rewardOnArrival(s2) + mdp.gamma * (mdp.isTerminal(s2) ? 0 : v[s2]);
                    m1 += p * y; m2 += p * y * y;
                }
                qa[a] = m1; va[a] = m2 - m1 * m1;
                mean += mdp.pi[s][a] * m1;
            }
            for (int a = 0; a < nA; a++) {
                pol[s] += mdp.pi[s][a] * (qa[a] - mean) * (qa[a] - mean);
                slip[s] += mdp.pi[s][a] * va[a];
            }
        }
        double[] d = new double[nS];
        d[s0] = 1;
        double vp = 0, vs = 0, g2 = 1;
        for (int t = 0; t < n; t++) {
            double[] d2 = new double[nS];
            for (int s = 0; s < nS; s++) {
                if (d[s] == 0 || mdp.isTerminal(s)) continue;
                vp += g2 * d[s] * pol[s]; vs += g2 * d[s] * slip[s];
                for (int a = 0; a < nA; a++)
                    for (int s2 = 0; s2 < nS; s2++) d2[s2] += d[s] * mdp.pi[s][a] * mdp.p[s][a][s2];
            }
            d = d2; g2 *= mdp.gamma * mdp.gamma;
        }
        return new double[] {vp, vs};
    }
}
