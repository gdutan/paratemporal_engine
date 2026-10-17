package sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import com.ms4systems.devs.core.model.AtomicModel;
import com.ms4systems.devs.exception.DEVSRuntimeException;

import examples.baseball.BaseballGame;
import examples.baseball.GameState;
import examples.rl.MdpWalker;
import examples.rl.TabularMdp;
import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;
import paradevs.engine.Transducer;
import paradevs.ms4.Snapshots;
import sandbox.fastcopy.FastModels;
import sandbox.fastcopy.ProtoEngine;
import sandbox.fastcopy.ProtoEngine.Config;
import sandbox.fastcopy.ProtoEngine.Copy;
import sandbox.fastcopy.ProtoEngine.Prof;
import sandbox.fastcopy.ProtoEngine.Result;
import sandbox.fastcopy.Verify;

/**
 * MEASUREMENT PROTOTYPE: profiling harness for docs/engine/ParaDEVS_FastCopy_Proposal.tex.
 *
 * Usage (after scripts/build_library.sh has compiled everything), from the repository root:
 *   java -Xmx512m -XX:ActiveProcessorCount=2 -cp "build/library/classes;lib/ms4-core.jar" sandbox.ProfileEngine
 *
 * Parts: (1) checks that the instrumented copy of the engine (sandbox.fastcopy.ProtoEngine) gives the
 * same results and departure counts as the real engine; (2) a per-phase time breakdown of a departure
 * under today's serialization; (3) wall time per copy strategy (timers off); (4) the verification mode
 * catching a deliberately incomplete paraCopy(); (5) a micro-benchmark of MS4 wrapping the suspension.
 */
public class ProfileEngine {

    static final int[] FL_STARTS = {0, 10, 18, 33, 44, 57};
    static final int FL_N = 6;
    static TabularMdp FL;
    static double[] V;

    /** xi for the n-step return: discounted return, current state, steps, gamma^steps (as NStepTargets.Ret). */
    static final class Ret {
        final double g, disc; final int state, steps;
        Ret(double g, int state, int steps, double disc) { this.g = g; this.state = state; this.steps = steps; this.disc = disc; }
        @Override public boolean equals(Object o) {
            return o instanceof Ret && Double.compare(g, ((Ret) o).g) == 0 && state == ((Ret) o).state && steps == ((Ret) o).steps;
        }
        @Override public int hashCode() { return Objects.hash(g, state, steps); }
    }

    static Transducer<Ret> returns(TabularMdp mdp) {
        return new Transducer<Ret>() {
            @Override public Ret initial() { return new Ret(0, -1, -1, 1); }
            @Override public Ret record(Ret xi, double t, List<OutputEvent> outs) {
                for (OutputEvent e : outs) {
                    if (!e.port.equals("state")) continue;
                    int s2 = (Integer) e.value;
                    if (xi.state < 0) { xi = new Ret(0, s2, 0, 1); continue; }
                    xi = new Ret(xi.g + xi.disc * mdp.rewardOnArrival(s2), s2, xi.steps + 1, xi.disc * mdp.gamma);
                }
                return xi;
            }
        };
    }

    static final Transducer<GameState> LAST_STATE = new Transducer<GameState>() {
        @Override public GameState initial() { return null; }
        @Override public GameState record(GameState xi, double t, List<OutputEvent> outs) {
            for (OutputEvent e : outs) if (e.port.equals("state")) xi = (GameState) e.value;
            return xi;
        }
    };

    // ------------------------------------------------------------------ workloads

    /** One way of running: copy strategy, hand-written copies, cached key plans, inline first option. */
    static final class Strat {
        final String label; final Copy copy; final boolean fast, keys, inline;
        Strat(String label, Copy copy, boolean fast, boolean keys, boolean inline) {
            this.label = label; this.copy = copy; this.fast = fast; this.keys = keys; this.inline = inline;
        }
    }

    /** A named workload: runs ProtoEngine over all its sub-runs with one strategy and returns the summed profile. */
    interface Workload { String name(); boolean exact(); Agg run(Strat s, boolean timers); }

    static final class Agg { final Prof prof = new Prof(); double value; }

    static Supplier<AtomicModel> walker(int s, boolean fast) {
        return fast ? () -> new FastModels.FastMdpWalker(FL.name, s, FL_N, MdpWalker.Mode.JOINT)
                    : () -> new MdpWalker(FL.name, s, FL_N, MdpWalker.Mode.JOINT);
    }

    static Workload frozenLake(String name, Supplier<SelectionRule> rule, boolean merge, int seeds, boolean exact) {
        return new Workload() {
            public String name() { return name; }
            public boolean exact() { return exact; }
            public Agg run(Strat st, boolean timers) {
                Agg a = new Agg();
                double[] v = V;
                for (int s : FL_STARTS) for (int i = 0; i < seeds; i++) {
                    Result<Ret> r = ProtoEngine.run(new Config<>(walker(s, st.fast), returns(FL)).selection(rule.get())
                        .merge(merge).seed(1000L * s + i).copy(st.copy).cachedKeys(st.keys).inline(st.inline).timers(timers));
                    a.prof.add(r.prof);
                    a.value += r.expectation(x -> x.g + (FL.isTerminal(x.state) ? 0 : x.disc * v[x.state]));
                }
                return a;
            }
        };
    }

    static Supplier<AtomicModel> baseball(int pa, boolean fast) {
        return fast ? () -> new FastModels.FastBaseball(BaseballGame.NORMAL, BaseballGame.SCORING, pa)
                    : () -> new BaseballGame(BaseballGame.NORMAL, BaseballGame.SCORING, pa);
    }

    static Workload baseballW(String name, int pa, boolean merge, int repeat) {
        return new Workload() {
            public String name() { return name; }
            public boolean exact() { return true; }
            public Agg run(Strat st, boolean timers) {
                Agg a = new Agg();
                for (int i = 0; i < repeat; i++) {
                    Result<GameState> r = ProtoEngine.run(new Config<>(baseball(pa, st.fast), LAST_STATE).merge(merge)
                        .copy(st.copy).cachedKeys(st.keys).inline(st.inline).timers(timers));
                    a.prof.add(r.prof);
                    a.value += r.expectation(g -> g.runs);
                }
                return a;
            }
        };
    }

    static boolean patchedPara() {
        try { paradevs.Para.class.getMethod("enter", List.class, java.util.function.BiFunction.class); return true; }
        catch (NoSuchMethodException e) { return false; }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        FL = TabularMdp.frozenLake8x8(0.99).epsilonSoftOptimal(0.2);
        V = FL.evaluatePolicy();
        int reps = Integer.getInteger("reps", 2);
        String parts = System.getProperty("parts", "123456");
        String only = System.getProperty("work", "");
        boolean inl = patchedPara();

        List<Workload> ws = new ArrayList<>();
        ws.add(frozenLake("FL exhaustive", SelectionRule::exhaustive, false, 1, true));
        ws.add(frozenLake("FL exhaustive+merge x8", SelectionRule::exhaustive, true, 8, true));
        ws.add(frozenLake("FL k=3 tree+merge x20", () -> SelectionRule.monteCarlo(3), true, 20, false));
        ws.add(baseballW("BB 5PA no merge", 5, false, 1));
        ws.add(baseballW("BB 6PA merge x6", 6, true, 6));
        if (!only.isEmpty()) ws.removeIf(w -> !w.name().startsWith(only));

        List<Strat> strats = new ArrayList<>();
        strats.add(new Strat("SERIAL (today)", Copy.SERIAL, false, false, false));
        strats.add(new Strat("SERIAL_TUNED", Copy.SERIAL_TUNED, false, false, false));
        strats.add(new Strat("REFLECT", Copy.REFLECT, false, false, false));
        strats.add(new Strat("REFLECT+keys", Copy.REFLECT, false, true, false));
        strats.add(new Strat("HOOK", Copy.HOOK, true, false, false));
        strats.add(new Strat("HOOK+keys", Copy.HOOK, true, true, false));
        if (inl) {
            strats.add(new Strat("REFLECT+keys+inline", Copy.REFLECT, false, true, true));
            strats.add(new Strat("HOOK+keys+inline", Copy.HOOK, true, true, true));
        }
        strats.add(new Strat("HOOK_VERIFY", Copy.HOOK_VERIFY, true, false, false));

        if (parts.contains("1")) crossCheck(inl);

        if (parts.contains("2")) {
            Strat[] prof = {strats.get(0), new Strat("REFLECT+keys", Copy.REFLECT, false, true, false),
                new Strat("HOOK+keys", Copy.HOOK, true, true, false)};
            for (Strat st : prof) {
                System.out.println("\n== (2) Per-departure breakdown, " + st.label + " (timers on, microseconds per departure)");
                System.out.printf("%-24s %8s %6s %7s %7s %7s %7s %7s %7s %7s %7s %8s %8s %7s%n", "workload", "deps", "susp%",
                    "restore", "node", "runOK", "runSus", "record", "key", "save", "engine", "total", "bytes", "wasted%");
                for (Workload w : ws) {
                    w.run(st, true);   // warm-up
                    Agg a = w.run(st, true);
                    Prof p = a.prof;
                    double d = p.departures;
                    double wasted = p.tRestoreSusp + p.tNodeSusp + p.tRunSusp;
                    System.out.printf("%-24s %8d %6.1f %7.2f %7.2f %7.2f %7.2f %7.2f %7.2f %7.2f %7.2f %8.2f %8d %7.1f%n", w.name(), p.departures,
                        100.0 * p.suspended / d, us(p.tRestore, d), us(p.tNode, d), us(p.tRunDone, d), us(p.tRunSusp, d), us(p.tRecord, d),
                        us(p.tKey, d), us(p.tSave, d), us(p.tWall - p.tTimed(), d), us(p.tWall, d),
                        p.saves == 0 ? 0 : p.bytesSaved / p.saves, 100.0 * wasted / p.tWall);
                }
            }
        }

        if (parts.contains("3")) {
            System.out.println("\n== (3) Wall time per strategy (timers off; best of " + reps + " runs for serialization strategies, of 7 for the fast ones): total ms, speed-up vs SERIAL, departures");
            for (Workload w : ws) {
                System.out.println(w.name() + ":");
                double base = 0, baseValue = Double.NaN;
                long baseDeps = -1;
                for (Strat st : strats) {
                    boolean slow = st.copy == Copy.SERIAL || st.copy == Copy.SERIAL_TUNED || st.copy == Copy.HOOK_VERIFY;
                    for (int r = 0; r < (slow ? 1 : 2); r++) w.run(st, false);   // warm-up
                    long best = Long.MAX_VALUE; Agg a = null;
                    for (int r = 0; r < (slow ? reps : 7); r++) { a = w.run(st, false); best = Math.min(best, a.prof.tWall); }
                    if (st == strats.get(0)) { base = best; baseValue = a.value; baseDeps = a.prof.departures; }
                    if (!st.inline && a.prof.departures != baseDeps)
                        throw new IllegalStateException(w.name() + " " + st.label + ": departures " + a.prof.departures + " != " + baseDeps);
                    boolean comparable = w.exact() || !st.inline;
                    if (comparable && Math.abs(a.value - baseValue) > 1e-9 * Math.max(1, Math.abs(baseValue)))
                        throw new IllegalStateException(w.name() + " " + st.label + ": value " + a.value + " != " + baseValue);
                    System.out.printf("   %-22s %10.1f ms  x%6.1f  %8d departures  %8.2f us/departure%s%n", st.label, best / 1e6, base / best,
                        a.prof.departures, us(best, a.prof.departures), comparable ? "" : "  (different random stream)");
                }
            }
        }

        if (parts.contains("4")) verificationDemo();
        if (parts.contains("5")) suspensionCost();
        if (parts.contains("6")) memory();
    }

    // ------------------------------------------------------------------ (6) memory per stored branch state

    static void memory() {
        System.out.println("\n== (6) Heap retained per stored branch state (20,000 states, after GC)");
        int n = 20_000;
        String[] labels = {"MdpWalker bytes (SERIAL)", "MdpWalker REFLECT copy", "FastMdpWalker HOOK copy",
            "BaseballGame bytes (SERIAL)", "BaseballGame REFLECT copy", "FastBaseball HOOK copy"};
        for (int kind = 0; kind < 6; kind++) {
            AtomicModel proto = kind < 3 ? walker(0, kind == 2).get() : baseball(6, kind == 5).get();
            Object[] keep = new Object[n];
            long before = used();
            for (int i = 0; i < n; i++) {
                if (kind == 0 || kind == 3) keep[i] = Snapshots.save(proto);
                else if (kind == 1 || kind == 4) keep[i] = ProtoEngine.reflectOrSerial(proto);
                else keep[i] = ProtoEngine.hookOrSerial(proto);
            }
            long after = used();
            System.out.printf("   %-28s %8.0f bytes/state%n", labels[kind], (after - before) / (double) n);
            if (keep[n - 1] == null) System.out.println("?");
        }
    }

    static long used() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }

    static double us(long ns, double count) { return ns / 1000.0 / count; }

    // ------------------------------------------------------------------ (1) cross-check with the real engine

    static void crossCheck(boolean inl) {
        System.out.println("== (1) Cross-check: ProtoEngine vs paradevs.engine.ParaEngine");
        double[] v = V;
        for (int s : FL_STARTS) for (boolean merge : new boolean[]{false, true}) for (boolean fast : new boolean[]{false, true}) {
            ParaResult<Ret> real = ParaEngine.run(ParaSpec.of(walker(s, false), returns(FL))
                .merge(merge ? ParaSpec.Merge.SAFE : ParaSpec.Merge.NONE));
            double rv = real.expectation(x -> x.g + (FL.isTerminal(x.state) ? 0 : x.disc * v[x.state]));
            for (Copy c : fast ? new Copy[]{Copy.HOOK, Copy.HOOK_VERIFY} : new Copy[]{Copy.SERIAL, Copy.SERIAL_TUNED, Copy.REFLECT}) {
                Result<Ret> r = ProtoEngine.run(new Config<>(walker(s, fast), returns(FL)).merge(merge).copy(c));
                double pv = r.expectation(x -> x.g + (FL.isTerminal(x.state) ? 0 : x.disc * v[x.state]));
                if (Math.abs(pv - rv) > 1e-12 || r.prof.departures != real.stats().departures || r.prof.merged != real.stats().merged)
                    throw new IllegalStateException("mismatch FL s=" + s + " merge=" + merge + " " + c + ": " + pv + " vs " + rv
                        + ", departures " + r.prof.departures + " vs " + real.stats().departures);
            }
            if (inl) {
                Result<Ret> r = ProtoEngine.run(new Config<>(walker(s, fast), returns(FL)).merge(merge).copy(fast ? Copy.HOOK : Copy.REFLECT).inline(true));
                double pv = r.expectation(x -> x.g + (FL.isTerminal(x.state) ? 0 : x.disc * v[x.state]));
                if (Math.abs(pv - rv) > 1e-12) throw new IllegalStateException("inline mismatch FL s=" + s + ": " + pv + " vs " + rv);
                if (!fast && merge && s == 0) System.out.println("   inline, FL s=0 merge: departures " + r.prof.departures + " vs " + real.stats().departures + " (same value)");
            }
        }
        for (int pa : new int[]{4, 6}) {
            ParaResult<GameState> real = ParaEngine.run(ParaSpec.of(baseball(pa, false), LAST_STATE).merge(ParaSpec.Merge.SAFE));
            double rv = real.expectation(g -> g.runs);
            for (Copy c : Copy.values()) {
                boolean fast = c == Copy.HOOK || c == Copy.HOOK_VERIFY;
                Result<GameState> r = ProtoEngine.run(new Config<>(baseball(pa, fast), LAST_STATE).merge(true).copy(c).cachedKeys(fast));
                double pv = r.expectation(g -> g.runs);
                if (Math.abs(pv - rv) > 1e-12 || r.prof.departures != real.stats().departures || r.prof.merged != real.stats().merged)
                    throw new IllegalStateException("mismatch baseball " + pa + " " + c + ": " + pv + " vs " + rv);
            }
            if (inl) {
                Result<GameState> r = ProtoEngine.run(new Config<>(baseball(pa, true), LAST_STATE).merge(true).copy(Copy.HOOK).cachedKeys(true).inline(true));
                double pv = r.expectation(g -> g.runs);
                if (Math.abs(pv - rv) > 1e-12) throw new IllegalStateException("inline mismatch baseball " + pa + ": " + pv + " vs " + rv);
                System.out.println("   inline, baseball " + pa + " PA merge: departures " + r.prof.departures + " vs " + real.stats().departures + " (same value)");
            }
        }
        System.out.println("   all strategies give the real engine's value, departures and merges (FrozenLake 6 starts x merge on/off; baseball 4 and 6 PA)");
        int sz = Snapshots.save(new MdpWalker(FL.name, 0, FL_N, MdpWalker.Mode.JOINT)).length;
        int bb = Snapshots.save(new BaseballGame(BaseballGame.NORMAL, BaseballGame.SCORING, 6)).length;
        System.out.println("   serialized size of a fresh model: MdpWalker " + sz + " bytes, BaseballGame " + bb + " bytes");
    }

    // ------------------------------------------------------------------ (4) verification mode

    static void verificationDemo() {
        System.out.println("\n== (4) Verification mode with a deliberately incomplete paraCopy() (forgets 'left')");
        int s = 55;   // next to the goal, so the 6-step return (no bootstrap) is positive
        Result<Ret> good = ProtoEngine.run(new Config<>(walker(s, false), returns(FL)).copy(Copy.SERIAL));
        Result<Ret> bad = ProtoEngine.run(new Config<>(() -> new FastModels.BrokenMdpWalker(FL.name, s, FL_N, MdpWalker.Mode.JOINT),
            returns(FL)).copy(Copy.HOOK));
        System.out.printf("   without verification: target %.6f (correct %.6f), departures %d (correct %d): silently wrong%n",
            bad.expectation(x -> x.g), good.expectation(x -> x.g), bad.prof.departures, good.prof.departures);
        try {
            ProtoEngine.run(new Config<>(() -> new FastModels.BrokenMdpWalker(FL.name, s, FL_N, MdpWalker.Mode.JOINT), returns(FL))
                .copy(Copy.HOOK_VERIFY));
            System.out.println("   with verification: NOT caught (unexpected)");
        } catch (Verify.CopyMismatch e) {
            System.out.println("   with verification: caught at the first copy: " + e.getMessage());
        }
        try {
            ProtoEngine.run(new Config<>(() -> new FastModels.AliasingMdpWalker(FL.name, s, FL_N, MdpWalker.Mode.JOINT), returns(FL))
                .copy(Copy.HOOK_VERIFY));
            System.out.println("   shared mutable array: NOT caught (unexpected)");
        } catch (Verify.CopyMismatch e) {
            System.out.println("   shared mutable array (copy check passes): caught after the first run: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ (5) suspension cost

    static void suspensionCost() {
        System.out.println("\n== (5) Cost of MS4 wrapping the suspension in a DEVSRuntimeException (with a stack trace)");
        Throwable cause = new Error("x", null, false, false) {};
        long sink = 0;
        for (int round = 0; round < 3; round++) {
            int n = 200_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) sink += deep(12, cause).getStackTrace().length > 0 ? 0 : 1;
            long t1 = System.nanoTime();
            for (int i = 0; i < n; i++) sink += deepNoTrace(12, cause) == null ? 1 : 0;
            long t2 = System.nanoTime();
            if (round == 2) System.out.printf("   new DEVSRuntimeException at stack depth ~20: %.2f us (with getStackTrace); %.3f us for a stackless Error%n",
                (t1 - t0) / 1000.0 / n, (t2 - t1) / 1000.0 / n);
        }
        int n = 200_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) sink += deep(12, cause).hashCode() & 1;
        long t1 = System.nanoTime();
        System.out.printf("   ... without reading the trace (what the engine pays): %.2f us   (sink %d)%n", (t1 - t0) / 1000.0 / n, sink & 1);
    }

    static Throwable deep(int d, Throwable cause) { return d == 0 ? new DEVSRuntimeException("Error in model MdpWalker", cause) : deep(d - 1, cause); }
    static Throwable deepNoTrace(int d, Throwable cause) { return d == 0 ? new Error("s", cause, false, false) {} : deepNoTrace(d - 1, cause); }
}
