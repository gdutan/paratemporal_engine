package sandbox.fastcopy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import com.ms4systems.devs.core.message.Message;
import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.model.AtomicModel;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;

import paradevs.Para;
import paradevs.engine.OutputEvent;
import paradevs.engine.SelectionRule;
import paradevs.engine.StopStatus;
import paradevs.engine.Transducer;
import paradevs.ms4.ModelNode;
import paradevs.ms4.Snapshots;
import paradevs.ms4.StateKeys;

/**
 * MEASUREMENT PROTOTYPE, not part of the library. A trimmed copy of paradevs.engine.ParaEngine (no
 * input trace, no acceptor: enough for MdpWalker and baseball) with System.nanoTime timers around each
 * phase of a departure and a pluggable way of copying branch states. Its results are checked against
 * the real engine by {@link ProfileEngine}. Nothing in src/main uses it.
 *
 * Copy strategies:
 * <ul>
 * <li>SERIAL: as the library today. A branch holds serialized bytes; every run deserializes them, and a
 *     completed run is serialized again.</li>
 * <li>SERIAL_TUNED: the same bytes, with a class-lookup cache on the input stream and a pre-sized
 *     output buffer (option b, "faster serializer" without new dependencies).</li>
 * <li>REFLECT: a branch holds the live model object of the run that produced it (never touched again);
 *     every run starts from a reflective deep copy of it ({@link ReflectCopy}); option b.</li>
 * <li>HOOK: like REFLECT, but models implementing {@link ParaCopyable} are copied by their own
 *     paraCopy() plus {@link Bookkeeping}; other models fall back to serialization. Option a.</li>
 * <li>HOOK_VERIFY: HOOK, and every copy is also made by serialization and the two states compared
 *     field by field (the proposed verification mode, {@link Verify}).</li>
 * </ul>
 * Option c, "inline" (Config.inline): a run that reaches a draw with no supplied value does not
 * suspend; it continues with the first selected option and queues the others for replay, so the
 * discovery run is no longer thrown away. This needs the patched Para in src/sandbox/patches.
 */
public final class ProtoEngine<X> {

    public enum Copy { SERIAL, SERIAL_TUNED, REFLECT, HOOK, HOOK_VERIFY }

    // ------------------------------------------------------------------ configuration

    public static final class Config<X> {
        final Supplier<? extends AtomicModel> model;
        final Transducer<X> transducer;
        SelectionRule selection = SelectionRule.exhaustive();
        boolean safeMerge = false;
        int roots = 1, horizon = Integer.MAX_VALUE;
        long seed = 1L;
        Copy copy = Copy.SERIAL;
        boolean cachedKeys = false;   // option c: StateKeys with per-class field plans
        boolean inline = false;       // option c: continue with the first option (needs the patched Para)
        boolean timers = true;

        public Config(Supplier<? extends AtomicModel> model, Transducer<X> transducer) { this.model = model; this.transducer = transducer; }
        public Config<X> selection(SelectionRule s) { selection = s; return this; }
        public Config<X> merge(boolean safe) { safeMerge = safe; return this; }
        public Config<X> roots(int n) { roots = n; return this; }
        public Config<X> horizon(int m) { horizon = m; return this; }
        public Config<X> seed(long s) { seed = s; return this; }
        public Config<X> copy(Copy c) { copy = c; return this; }
        public Config<X> cachedKeys(boolean b) { cachedKeys = b; return this; }
        public Config<X> inline(boolean b) { inline = b; return this; }
        public Config<X> timers(boolean b) { timers = b; return this; }
    }

    /** Phase timers (nanoseconds) and counters. */
    public static final class Prof {
        public long departures, suspended, choiceNodes, merged, leaves, maxLive;
        public long tRestore, tNode, tRunDone, tRunSusp, tKey, tSave, tRecord, tWall;
        public long tRestoreSusp, tNodeSusp;     // part of tRestore / tNode spent on runs that then suspended
        public long bytesSaved, saves;

        public void add(Prof o) {
            departures += o.departures; suspended += o.suspended; choiceNodes += o.choiceNodes; merged += o.merged;
            leaves += o.leaves; maxLive = Math.max(maxLive, o.maxLive);
            tRestore += o.tRestore; tNode += o.tNode; tRunDone += o.tRunDone; tRunSusp += o.tRunSusp; tKey += o.tKey;
            tSave += o.tSave; tRecord += o.tRecord; tWall += o.tWall; tRestoreSusp += o.tRestoreSusp; tNodeSusp += o.tNodeSusp;
            bytesSaved += o.bytesSaved; saves += o.saves;
        }
        public long tTimed() { return tRestore + tNode + tRunDone + tRunSusp + tKey + tSave + tRecord; }
    }

    /** The collector: weight per (xi, stop status). */
    public static final class Result<X> {
        public final Map<List<Object>, Double> collector = new LinkedHashMap<>();
        public double residual;
        public final Prof prof = new Prof();
        public double expectation(ToDoubleFunction<X> f) {
            double s = 0;
            for (Map.Entry<List<Object>, Double> e : collector.entrySet()) {
                @SuppressWarnings("unchecked") X xi = (X) e.getKey().get(0);
                s += e.getValue() * f.applyAsDouble(xi);
            }
            return s;
        }
        void collect(X xi, StopStatus h, double w) { collector.merge(java.util.Arrays.asList(xi, h), w, Double::sum); prof.leaves++; }
    }

    // ------------------------------------------------------------------ records

    enum Kind { INIT, INTERNAL }

    static final class Branch<X> {
        final Object state;      // byte[] (serial strategies) or a live, frozen model object
        final Object stateKey;
        final double theta, tN;
        final int n;
        final X xi;
        final double w;
        Branch(Object state, Object stateKey, double theta, double tN, int n, X xi, double w) {
            this.state = state; this.stateKey = stateKey; this.theta = theta; this.tN = tN; this.n = n; this.xi = xi; this.w = w;
        }
        Branch<X> withWeight(double w2) { return new Branch<>(state, stateKey, theta, tN, n, xi, w2); }
    }

    static final class Continuation<X> {
        final Branch<X> parent; final Kind kind; final double t; final List<Object> supplied; final Para.Pending pending; final double w;
        Local<X> inlineResult;   // inline mode: the run that continued with the first option, and its queued siblings
        Continuation(Branch<X> parent, Kind kind, double t, List<Object> supplied, Para.Pending pending, double w) {
            this.parent = parent; this.kind = kind; this.t = t; this.supplied = supplied; this.pending = pending; this.w = w;
        }
    }

    static final class Local<X> {
        final Branch<X> completed; final Para.Pending pending;
        List<Continuation<X>> siblings;   // inline mode: other options of the draws made, to replay
        Local(Branch<X> completed, Para.Pending pending) { this.completed = completed; this.pending = pending; }
    }

    // ------------------------------------------------------------------ run

    private final Config<X> cfg;
    private final Result<X> result = new Result<>();
    private final Prof p = result.prof;
    private final Random rng;
    private final TreeMap<Long, Branch<X>> live = new TreeMap<>();
    private long nextName = 0;

    private ProtoEngine(Config<X> cfg) { this.cfg = cfg; this.rng = new Random(scramble(cfg.seed)); }

    public static <X> Result<X> run(Config<X> cfg) { return new ProtoEngine<>(cfg).execute(); }

    static long scramble(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private long now() { return cfg.timers ? System.nanoTime() : 0L; }

    Result<X> execute() {
        long start = System.nanoTime();
        installRoots();
        List<Long> requests = new ArrayList<>();
        List<Continuation<X>> choices = new ArrayList<>();
        while (!live.isEmpty()) {
            // Gamma_PT
            List<Branch<X>> children = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                long name = requests.get(i);
                Continuation<X> c = choices.get(i);
                Branch<X> parent = live.get(name);
                if (c == null) collectStop(parent);
                else for (Branch<X> k : c.inlineResult != null ? expandInline(c.inlineResult) : doExpansion(c)) {
                    StopStatus h = stopStatus(k);
                    if (h == StopStatus.LIVE) children.add(k); else result.collect(k.xi, h, k.w);
                }
                live.remove(name);
            }
            for (Branch<X> k : merge(children)) live.put(nextName++, k);
            p.maxLive = Math.max(p.maxLive, live.size());
            requests = new ArrayList<>();
            choices = new ArrayList<>();
            if (live.isEmpty()) break;
            double t = Double.POSITIVE_INFINITY;
            for (Branch<X> k : live.values()) t = Math.min(t, k.tN);
            if (Double.isInfinite(t)) throw new IllegalStateException("live branch with no next event");
            List<Long> due = new ArrayList<>();
            for (Map.Entry<Long, Branch<X>> e : live.entrySet()) if (Double.compare(e.getValue().tN, t) == 0) due.add(e.getKey());
            for (long d : due) {   // Advance
                Branch<X> k = live.get(d);
                Local<X> loc = runLocal(k, Kind.INTERNAL, t, Collections.emptyList(), k.w);
                if (loc.siblings != null || (cfg.inline && loc.completed == null)) {
                    Continuation<X> c = new Continuation<>(k, Kind.INTERNAL, t, Collections.emptyList(), null, k.w);
                    c.inlineResult = loc;
                    requests.add(d); choices.add(c);
                } else if (loc.pending != null) {
                    requests.add(d); choices.add(new Continuation<>(k, Kind.INTERNAL, t, Collections.emptyList(), loc.pending, k.w));
                } else {
                    live.put(d, loc.completed);
                    if (stopStatus(loc.completed) != StopStatus.LIVE) { requests.add(d); choices.add(null); }
                }
            }
        }
        p.tWall = System.nanoTime() - start;
        return result;
    }

    private void collectStop(Branch<X> k) { result.collect(k.xi, stopStatus(k), k.w); }

    void installRoots() {
        AtomicModel fresh = cfg.model.get();
        Object state = isSerial() ? Snapshots.save(fresh) : fresh;
        Branch<X> pre = new Branch<>(state, null, 0.0, Double.NaN, 0, cfg.transducer.initial(), 1.0 / cfg.roots);
        List<Branch<X>> roots = new ArrayList<>();
        for (int i = 0; i < cfg.roots; i++) {
            Local<X> loc = runLocal(pre, Kind.INIT, 0.0, Collections.emptyList(), pre.w);
            if (cfg.inline) roots.addAll(expandInline(loc));
            else if (loc.completed != null) roots.add(loc.completed);
            else roots.addAll(doExpansion(new Continuation<>(pre, Kind.INIT, 0.0, Collections.emptyList(), loc.pending, pre.w)));
        }
        List<Branch<X>> liveRoots = new ArrayList<>();
        for (Branch<X> k : roots) {
            StopStatus h = stopStatus(k);
            if (h == StopStatus.LIVE) liveRoots.add(k); else result.collect(k.xi, h, k.w);
        }
        for (Branch<X> k : merge(liveRoots)) live.put(nextName++, k);
    }

    List<Branch<X>> doExpansion(Continuation<X> start) {
        List<Branch<X>> completed = new ArrayList<>();
        List<Continuation<X>> work = new ArrayList<>();
        work.add(start);
        while (!work.isEmpty()) {
            Continuation<X> u = work.remove(work.size() - 1);
            SelectionRule.Options opts = cfg.selection.select(u.pending.label, u.pending.law, rng, u.w);
            p.choiceNodes++;
            result.residual += u.w * opts.omitted;
            for (int i = 0; i < opts.values.size(); i++) {
                double alpha = opts.factors.get(i);
                if (!(alpha > 0)) continue;
                List<Object> supplied = new ArrayList<>(u.supplied);
                supplied.add(opts.values.get(i));
                double w = u.w * alpha;
                Local<X> loc = runLocal(u.parent, u.kind, u.t, supplied, w);
                if (loc.completed != null) completed.add(loc.completed);
                else work.add(new Continuation<>(u.parent, u.kind, u.t, supplied, loc.pending, w));
            }
        }
        return completed;
    }

    /** Inline mode: the first run's result plus breadth-first replays of all queued sibling options. */
    List<Branch<X>> expandInline(Local<X> first) {
        List<Branch<X>> out = new ArrayList<>();
        java.util.ArrayDeque<Continuation<X>> q = new java.util.ArrayDeque<>();
        if (first.completed != null) out.add(first.completed);
        if (first.siblings != null) q.addAll(first.siblings);
        while (!q.isEmpty()) {
            Continuation<X> u = q.poll();
            Local<X> l = runLocal(u.parent, u.kind, u.t, u.supplied, u.w);
            if (l.completed != null) out.add(l.completed);
            if (l.siblings != null) q.addAll(l.siblings);
        }
        return out;
    }

    private static java.lang.reflect.Method ENTER_INLINE;

    @SuppressWarnings("unchecked")
    private Para.DrawContext enterInline(Branch<X> parent, Kind kind, double t, List<Object> supplied, double w,
                                         List<Continuation<X>> sibs, double[] wRun) {
        List<Object> path = new ArrayList<>(supplied);
        java.util.function.BiFunction<String, paradevs.Law<?>, Object> decider = (label, law) -> {
            SelectionRule.Options opts = cfg.selection.select(label, law, rng, wRun[0]);
            p.choiceNodes++;
            result.residual += wRun[0] * opts.omitted;
            int first = -1;
            for (int i = 0; i < opts.values.size(); i++) {
                double alpha = opts.factors.get(i);
                if (!(alpha > 0)) continue;
                if (first < 0) { first = i; continue; }
                List<Object> sup = new ArrayList<>(path);
                sup.add(opts.values.get(i));
                sibs.add(new Continuation<>(parent, kind, t, sup, null, wRun[0] * alpha));
            }
            if (first < 0) return null;
            wRun[0] *= opts.factors.get(first);
            path.add(opts.values.get(first));
            return opts.values.get(first);
        };
        try {
            if (ENTER_INLINE == null) ENTER_INLINE = Para.class.getMethod("enter", List.class, java.util.function.BiFunction.class);
            return (Para.DrawContext) ENTER_INLINE.invoke(null, supplied, decider);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("inline mode needs the patched Para first on the classpath (src/sandbox/patches)", e);
        }
    }

    Local<X> runLocal(Branch<X> parent, Kind kind, double t, List<Object> supplied, double w) {
        p.departures++;
        long t0 = now();
        byte[] parentBefore = cfg.copy == Copy.HOOK_VERIFY ? Snapshots.save((AtomicModel) parent.state) : null;
        AtomicModel model = restore(parent.state);
        long t1 = now();
        ModelNode node = ModelNode.of(model);
        long t2 = now();
        p.tRestore += t1 - t0; p.tNode += t2 - t1;
        List<OutputEvent> outputs = new ArrayList<>();
        List<Continuation<X>> sibs = cfg.inline ? new ArrayList<>() : null;
        double[] wRun = {w};
        Para.DrawContext ctx = cfg.inline ? enterInline(parent, kind, t, supplied, w, sibs, wRun) : Para.enter(supplied);
        try {
            if (kind == Kind.INIT) node.init(t);
            else {
                for (Message<?> m : node.output(t)) outputs.add(new OutputEvent(m.getPort().getName(), m.getData()));
                node.transition(t, MessageBag.EMPTY);
            }
        } catch (Throwable th) {
            if (ctx.pending() == null) throw new IllegalStateException("Model failed during " + kind + " at t=" + t + ": " + th, th);
        } finally {
            Para.exit();
        }
        long t3 = now();
        if (parentBefore != null && !java.util.Arrays.equals(parentBefore, Snapshots.save((AtomicModel) parent.state)))
            throw new Verify.CopyMismatch("running a copy changed its parent " + ((AtomicModel) parent.state).getName()
                + ": paraCopy() shares mutable state between the copy and the original");
        if (ctx.pending() != null) {
            p.suspended++; p.tRunSusp += t3 - t2; p.tRestoreSusp += t1 - t0; p.tNodeSusp += t2 - t1;
            Local<X> l = new Local<>(null, ctx.pending());
            if (sibs != null && !sibs.isEmpty()) l.siblings = sibs;
            return l;
        }
        p.tRunDone += t3 - t2;
        if (ctx.used() != supplied.size()) throw new IllegalStateException("replay used fewer values than supplied");
        X xi2 = cfg.transducer.record(parent.xi, t, outputs);
        long t4 = now();
        Object key = cfg.safeMerge ? (cfg.cachedKeys ? FastKeys.of(model) : StateKeys.of(model)) : null;
        long t5 = now();
        Object state = save(model);
        long t6 = now();
        p.tRecord += t4 - t3; p.tKey += t5 - t4; p.tSave += t6 - t5;
        Local<X> done = new Local<>(new Branch<>(state, key, t, node.tN(), parent.n + 1, xi2, wRun[0]), null);
        if (sibs != null && !sibs.isEmpty()) done.siblings = sibs;
        return done;
    }

    StopStatus stopStatus(Branch<X> k) {
        if (k.n >= cfg.horizon) return StopStatus.HORIZON;
        if (Double.isInfinite(k.tN)) return StopStatus.PASSIVE;
        return StopStatus.LIVE;
    }

    List<Branch<X>> merge(List<Branch<X>> children) {
        if (!cfg.safeMerge || children.size() < 2) return children;
        Map<List<Object>, Branch<X>> groups = new LinkedHashMap<>();
        for (Branch<X> k : children) {
            List<Object> key = java.util.Arrays.asList(k.stateKey, k.theta, 0, k.n, k.xi);
            Branch<X> prev = groups.get(key);
            if (prev == null) groups.put(key, k);
            else { groups.put(key, prev.withWeight(prev.w + k.w)); p.merged++; }
        }
        return new ArrayList<>(groups.values());
    }

    // ------------------------------------------------------------------ copy strategies

    private boolean isSerial() { return cfg.copy == Copy.SERIAL || cfg.copy == Copy.SERIAL_TUNED; }

    private Object save(AtomicModel m) {
        switch (cfg.copy) {
            case SERIAL: { byte[] b = Snapshots.save(m); p.bytesSaved += b.length; p.saves++; return b; }
            case SERIAL_TUNED: { byte[] b = Tuned.save(m); p.bytesSaved += b.length; p.saves++; return b; }
            default: return m;   // the finished working copy becomes the frozen branch state
        }
    }

    private AtomicModel restore(Object state) {
        switch (cfg.copy) {
            case SERIAL: return Snapshots.restore((byte[]) state);
            case SERIAL_TUNED: return Tuned.restore((byte[]) state);
            case REFLECT: return reflectOrSerial((AtomicModel) state);
            case HOOK: return hookOrSerial((AtomicModel) state);
            default: {
                AtomicModel a = hookOrSerial((AtomicModel) state);
                AtomicModel b = Snapshots.restore(Snapshots.save((AtomicModel) state));
                Verify.sameState(a, b);
                return a;
            }
        }
    }

    private static final Map<Class<?>, Boolean> REFLECT_FAILS = new ConcurrentHashMap<>();

    public static AtomicModel reflectOrSerial(AtomicModel m) {
        if (!REFLECT_FAILS.containsKey(m.getClass())) {
            try { return ReflectCopy.copy(m); }
            catch (ReflectCopy.Unsupported e) { REFLECT_FAILS.put(m.getClass(), true); System.err.println("reflect copy unsupported: " + e.getMessage()); }
        }
        return Snapshots.restore(Snapshots.save(m));
    }

    public static AtomicModel hookOrSerial(AtomicModel m) {
        if (m instanceof ParaCopyable) {
            AtomicModel c = ((ParaCopyable) m).paraCopy();
            if (c == m || c.getClass() != m.getClass()) throw new IllegalStateException("paraCopy() must return a new " + m.getClass().getName());
            Bookkeeping.copy(m, c);
            return c;
        }
        return Snapshots.restore(Snapshots.save(m));
    }

    /** SERIAL_TUNED: pre-sized buffer and a cached class lookup (resolveClass is a Class.forName per descriptor). */
    static final class Tuned {
        private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<>();
        private static final ThreadLocal<ByteArrayOutputStream> BUF = ThreadLocal.withInitial(() -> new ByteArrayOutputStream(4096));

        static byte[] save(Serializable o) {
            ByteArrayOutputStream bos = BUF.get();
            bos.reset();
            try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
                out.writeObject(o);
                out.flush();
                return bos.toByteArray();
            } catch (IOException e) { throw new IllegalStateException(e); }
        }

        @SuppressWarnings("unchecked")
        static <T> T restore(byte[] bytes) {
            try (ObjectInputStream in = new CachingInput(new ByteArrayInputStream(bytes))) {
                return (T) in.readObject();
            } catch (IOException | ClassNotFoundException e) { throw new IllegalStateException(e); }
        }

        static final class CachingInput extends ObjectInputStream {
            CachingInput(InputStream in) throws IOException { super(in); }
            @Override protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                Class<?> c = CLASSES.get(desc.getName());
                if (c == null) { c = super.resolveClass(desc); CLASSES.put(desc.getName(), c); }
                return c;
            }
        }
    }

    /** Is the model a coupled model? (used by the harness for reporting). */
    static boolean coupled(AtomicModel m) { return m instanceof CoupledModelImpl; }
}
