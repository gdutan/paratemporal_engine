package examples.rl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;

/**
 * Shared harness for the hybrid-selection and cost-growth studies ({@link CompareHybrid},
 * {@link CostGrowth}, {@link VerifyHybrid}): per-draw selection rules for {@link MdpWalker}'s two
 * draws, replicated runs on a small thread pool, and resumable CSV output.
 */
final class HybridRuns {
    private HybridRuns() {}

    static final ParaSpec.Merge NONE = ParaSpec.Merge.NONE, SAFE = ParaSpec.Merge.SAFE;

    /** A rule for the "action" draw and one for the "transition" (slip) draw, via SelectionRule.hybrid. */
    static SelectionRule perDraw(SelectionRule action, SelectionRule slip) {
        return SelectionRule.hybrid(Map.of("action", action, "transition", slip), SelectionRule.exhaustive());
    }

    /** One run: the n-step target (V bootstraps live leaves) and its work, or work = -1 if maxLive was exceeded. */
    static double[] once(TabularMdp mdp, int start, int n, double[] v, MdpWalker.Mode mode, SelectionRule rule,
                         ParaSpec.Merge merge, int roots, long seed, long maxLive) {
        return once(mdp, start, n, v, mode, rule, merge, roots, seed, maxLive, false);
    }

    /** As above; {@code lagFree} selects {@link LagFreeWalker} and its lean observation. */
    static double[] once(TabularMdp mdp, int start, int n, double[] v, MdpWalker.Mode mode, SelectionRule rule,
                         ParaSpec.Merge merge, int roots, long seed, long maxLive, boolean lagFree) {
        if (lagFree) {
            ParaSpec<LagFreeWalker.Obs> spec = ParaSpec.of(() -> new LagFreeWalker(mdp.name, start, n, mode),
                    LagFreeWalker.returns(mdp, v))
                .selection(rule).merge(merge).roots(roots).seed(seed).maxLive(maxLive);
            try {
                ParaResult<LagFreeWalker.Obs> r = ParaEngine.run(spec);
                return new double[] {r.expectation(x -> x.g), r.stats().departures, r.stats().maxLive};
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("maxLive")) return new double[] {Double.NaN, -1, maxLive};
                throw e;
            }
        }
        ParaSpec<NStepTargets.Ret> spec = ParaSpec.of(() -> new MdpWalker(mdp.name, start, n, mode), NStepTargets.returns(mdp))
            .selection(rule).merge(merge).roots(roots).seed(seed).maxLive(maxLive);
        try {
            ParaResult<NStepTargets.Ret> r = ParaEngine.run(spec);
            double value = r.expectation(x -> x.g + (mdp.isTerminal(x.state) ? 0 : x.disc * v[x.state]));
            return new double[] {value, r.stats().departures, r.stats().maxLive};
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("maxLive")) return new double[] {Double.NaN, -1, maxLive};
            throw e;
        }
    }

    /** Summary of replicated runs from one start state. */
    static final class Stat {
        int reps; double mean, var, work, maxLive;
        double se() { return reps > 1 ? Math.sqrt(var / reps) : 0; }
    }

    static final int THREADS = Math.max(1, Math.min(3, Integer.getInteger("threads", 3)));
    static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r); t.setDaemon(true); return t;
    });

    /** Run {@code reps} replications (seeds seedBase + i) on the pool. */
    static Stat replicate(TabularMdp mdp, int start, int n, double[] v, MdpWalker.Mode mode, SelectionRule rule,
                          ParaSpec.Merge merge, int roots, int reps, long seedBase, long maxLive) {
        return replicate(mdp, start, n, v, mode, rule, merge, roots, reps, seedBase, maxLive, false);
    }

    static Stat replicate(TabularMdp mdp, int start, int n, double[] v, MdpWalker.Mode mode, SelectionRule rule,
                          ParaSpec.Merge merge, int roots, int reps, long seedBase, long maxLive, boolean arrival) {
        List<Future<double[]>> fs = new ArrayList<>();
        for (int i = 0; i < reps; i++) {
            long seed = seedBase + i;
            fs.add(POOL.submit(() -> once(mdp, start, n, v, mode, rule, merge, roots, seed, maxLive, arrival)));
        }
        double sum = 0, sq = 0, w = 0, ml = 0;
        try {
            for (Future<double[]> f : fs) {
                double[] x = f.get();
                if (x[1] < 0) { Stat s = new Stat(); s.reps = 0; s.work = -1; s.maxLive = maxLive; return s; }
                sum += x[0]; sq += x[0] * x[0]; w += x[1]; ml = Math.max(ml, x[2]);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        Stat s = new Stat();
        s.reps = reps; s.mean = sum / reps; s.work = w / reps; s.maxLive = ml;
        s.var = reps > 1 ? Math.max(0, (sq - reps * s.mean * s.mean) / (reps - 1)) : 0;
        return s;
    }

    /** Keys (first {@code keyCols} columns joined by ',') of the rows already in a CSV, for resuming. */
    static Set<String> doneKeys(Path csv, int keyCols) throws IOException {
        Set<String> keys = new HashSet<>();
        if (!Files.exists(csv)) return keys;
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            if (f.length >= keyCols) keys.add(String.join(",", java.util.Arrays.copyOf(f, keyCols)));
        }
        return keys;
    }

    static void append(Path csv, String header, List<String> rows) throws IOException {
        Files.createDirectories(csv.getParent());
        if (!Files.exists(csv)) Files.write(csv, List.of(header));
        Files.write(csv, rows, java.nio.file.StandardOpenOption.APPEND);
    }

    static int[] ints(String prop, int[] dflt) {
        String s = System.getProperty(prop);
        if (s == null || s.isBlank()) return dflt;
        String[] p = s.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }
}
