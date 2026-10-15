package paradevs.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.ms4systems.devs.core.message.Message;
import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.AtomicModel;

import paradevs.Para;
import paradevs.ms4.ModelNode;
import paradevs.ms4.Snapshots;
import paradevs.ms4.StateKeys;

/**
 * The paratemporal engine of main_paratemporal (Algorithms "unified", "main", "Gamma_PT",
 * "Advance", "doExpansion") over MS4 models.
 *
 * Suspension and resumption follow the derandomized-departure convention: a suspended
 * continuation is (persistent parent state, departure type and arguments, the values already
 * supplied, the pending law). Resume(u', v) re-runs the departure from the parent state with the
 * supplied values extended by v. This writes each requested value once and reuses every
 * realized value, as RunLocal requires, without a pausable program.
 */
public final class ParaEngine {
    private ParaEngine() {}

    public static <X> ParaResult<X> run(ParaSpec<X> spec) {
        if (spec.roots > 1 && spec.merge == ParaSpec.Merge.SAFE)
            throw new IllegalArgumentException("Safe merging with " + spec.roots + " roots would merge the independent "
                + "Monte Carlo samples into one path (the expectation is preserved, but the estimate collapses). "
                + "Use Merge.NONE with Monte Carlo roots.");
        return new Run<>(spec).execute();
    }

    // ------------------------------------------------------------------ records

    enum Kind { INIT, INTERNAL, EXTERNAL, CONFLUENT }

    /** Persistent branch record k = (eta, t, theta, j, n, xi, w); eta is held as bytes. */
    static final class Branch<X> {
        final byte[] state;
        final Object stateKey;   // only under SAFE merging
        final double theta;      // time of the latest persistent entry
        final double tN;         // absolute next internal event time of the model
        final int j, n;
        final X xi;
        final double w;
        Branch(byte[] state, Object stateKey, double theta, double tN, int j, int n, X xi, double w) {
            this.state = state; this.stateKey = stateKey; this.theta = theta; this.tN = tN;
            this.j = j; this.n = n; this.xi = xi; this.w = w;
        }
        Branch<X> withWeight(double w2) { return new Branch<>(state, stateKey, theta, tN, j, n, xi, w2); }
    }

    /** A departure suspended at a choice point (the payload rho of a choice request). */
    static final class Continuation<X> {
        final Branch<X> parent;   // persistent pre-departure record
        final Kind kind;
        final double t;
        final List<Object> supplied;
        final Para.Pending pending;
        final double w;
        Continuation(Branch<X> parent, Kind kind, double t, List<Object> supplied, Para.Pending pending, double w) {
            this.parent = parent; this.kind = kind; this.t = t; this.supplied = supplied; this.pending = pending; this.w = w;
        }
    }

    /** Outcome of RunLocal: a completed persistent entry, or a suspension. */
    static final class Local<X> {
        final Branch<X> completed;
        final Para.Pending pending;
        Local(Branch<X> completed, Para.Pending pending) { this.completed = completed; this.pending = pending; }
    }

    /** One request from the latest simulator step: a stop or a choice. */
    static final class Request<X> {
        final long name;
        final StopStatus stop;            // non-null for a stop request
        final Continuation<X> choice;     // non-null for a choice request
        Request(long name, StopStatus stop, Continuation<X> choice) { this.name = name; this.stop = stop; this.choice = choice; }
    }

    // ------------------------------------------------------------------ run

    static final class Run<X> {
        final ParaSpec<X> spec;
        final ParaResult<X> result = new ParaResult<>();
        final Random rng;
        final TreeMap<Long, Branch<X>> live = new TreeMap<>();   // L, iterated in name order
        long nextName = 0;

        Run(ParaSpec<X> spec) { this.spec = spec; this.rng = new Random(scramble(spec.seed)); }

        /**
         * SplitMix64 finalizer. java.util.Random gives nearly equal first values for nearby seeds
         * (seeds 7, 8, 9, ... never drew the first atom of a 4-atom law), so replications seeded
         * 1, 2, 3, ... were correlated and their average was biased. Scrambling makes nearby
         * seeds independent.
         */
        static long scramble(long seed) {
            long z = seed + 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        ParaResult<X> execute() {
            long start = System.currentTimeMillis();
            List<Request<X>> requests = new ArrayList<>();
            installRoots();
            while (!live.isEmpty()) {
                gamma(requests);                                       // Gamma_PT
                requests = new ArrayList<>();
                if (live.isEmpty()) break;
                double t = Double.POSITIVE_INFINITY;                   // chronological AP
                for (Branch<X> k : live.values()) t = Math.min(t, nextTime(k));
                if (Double.isInfinite(t))
                    throw new IllegalStateException("Live branch with no next event; StopStatus should have ended it");
                List<Long> due = new ArrayList<>();
                for (Map.Entry<Long, Branch<X>> e : live.entrySet())
                    if (Double.compare(nextTime(e.getValue()), t) == 0) due.add(e.getKey());
                for (long d : due) {
                    Request<X> q = advance(d, live.get(d), t);
                    if (q != null) requests.add(q);
                }
            }
            result.stats.millis = System.currentTimeMillis() - start;
            return result;
        }

        /** Generate weighted persistent roots: run initialize() under the selection rule. */
        void installRoots() {
            byte[] fresh = Snapshots.save(spec.model.get());
            Branch<X> pre = new Branch<>(fresh, null, 0.0, Double.NaN, 0, 0, spec.transducer.initial(), 1.0 / spec.roots);
            List<Branch<X>> roots = new ArrayList<>();
            for (int i = 0; i < spec.roots; i++) {
                Local<X> loc = runLocal(pre, Kind.INIT, 0.0, Collections.emptyList(), pre.w);
                if (loc.completed != null) roots.add(loc.completed);
                else roots.addAll(doExpansion(new Continuation<>(pre, Kind.INIT, 0.0, Collections.emptyList(), loc.pending, pre.w)));
            }
            List<Branch<X>> liveRoots = new ArrayList<>();
            for (Branch<X> k : roots) {
                StopStatus h = stopStatus(k);
                if (h == StopStatus.LIVE) liveRoots.add(k); else result.collect(k.xi, h, k.w);
            }
            for (Branch<X> k : merge(liveRoots)) live.put(nextName++, k);
            result.stats.maxLive = Math.max(result.stats.maxLive, live.size());
        }

        /** Gamma_PT: resolve requests in name order, collect stops, replace parents, merge children. */
        void gamma(List<Request<X>> requests) {
            List<Branch<X>> children = new ArrayList<>();
            requests.sort((a, b) -> Long.compare(a.name, b.name));
            for (Request<X> q : requests) {
                Branch<X> parent = live.get(q.name);
                if (q.stop != null) {
                    result.collect(parent.xi, q.stop, parent.w);
                } else {
                    for (Branch<X> k : doExpansion(q.choice)) {
                        StopStatus h = stopStatus(k);
                        if (h == StopStatus.LIVE) children.add(k); else result.collect(k.xi, h, k.w);
                    }
                }
                live.remove(q.name);   // parent leaves only after its mass is accounted for
            }
            for (Branch<X> k : merge(children)) live.put(nextName++, k);
            result.stats.maxLive = Math.max(result.stats.maxLive, live.size());
            if (live.size() > spec.maxLive)
                throw new IllegalStateException("Live branches exceed maxLive=" + spec.maxLive + "; lower the horizon or merge");
        }

        /** Advance: run branch d's next departure until a request or a completed entry. */
        Request<X> advance(long d, Branch<X> k, double t) {
            double a = spec.trace.timeAt(k.j);
            Kind kind = a < k.tN ? Kind.EXTERNAL : (k.tN < a ? Kind.INTERNAL : Kind.CONFLUENT);
            Local<X> loc = runLocal(k, kind, t, Collections.emptyList(), k.w);
            if (loc.pending != null)
                return new Request<>(d, null, new Continuation<>(k, kind, t, Collections.emptyList(), loc.pending, k.w));
            live.put(d, loc.completed);
            StopStatus h = stopStatus(loc.completed);
            return h == StopStatus.LIVE ? null : new Request<>(d, h, null);
        }

        /** doExpansion: resolve one departure through its local choices. */
        List<Branch<X>> doExpansion(Continuation<X> start) {
            List<Branch<X>> completed = new ArrayList<>();
            List<Continuation<X>> work = new ArrayList<>();
            work.add(start);
            while (!work.isEmpty()) {
                Continuation<X> u = work.remove(work.size() - 1);
                SelectionRule.Options opts = spec.selection.select(u.pending.label, u.pending.law, rng, u.w);
                result.stats.choiceNodes++;
                result.residual += u.w * opts.omitted;
                for (int i = 0; i < opts.values.size(); i++) {
                    double alpha = opts.factors.get(i);
                    if (!(alpha > 0)) continue;
                    List<Object> supplied = new ArrayList<>(u.supplied);
                    supplied.add(opts.values.get(i));
                    double w = u.w * alpha;                               // TransMass
                    Local<X> loc = runLocal(u.parent, u.kind, u.t, supplied, w);
                    if (loc.completed != null) completed.add(loc.completed);
                    else work.add(new Continuation<>(u.parent, u.kind, u.t, supplied, loc.pending, w));
                }
            }
            return completed;
        }

        /**
         * RunLocal: restore the parent's persistent state, replay the departure with the supplied
         * values, and stop at the next non-degenerate draw or at the completed entry.
         */
        Local<X> runLocal(Branch<X> parent, Kind kind, double t, List<Object> supplied, double w) {
            result.stats.departures++;
            AtomicModel model = Snapshots.restore(parent.state);
            ModelNode node = ModelNode.of(model);
            List<OutputEvent> outputs = new ArrayList<>();
            Para.DrawContext ctx = Para.enter(supplied);
            try {
                switch (kind) {
                    case INIT:
                        node.init(t);
                        break;
                    case INTERNAL:
                        record(node.output(t), outputs);   // psλ: emit the stored output
                        node.transition(t, MessageBag.EMPTY);
                        break;
                    case EXTERNAL:
                        node.transition(t, inputBag(model, spec.trace.get(parent.j), t));
                        break;
                    case CONFLUENT:
                        record(node.output(t), outputs);   // old output recorded once, then successor
                        node.transition(t, inputBag(model, spec.trace.get(parent.j), t));
                        break;
                }
            } catch (Throwable th) {
                if (ctx.pending() != null) return new Local<>(null, ctx.pending());
                throw new IllegalStateException("Model failed during " + kind + " at t=" + t + ": " + th, th);
            } finally {
                Para.exit();
            }
            if (ctx.pending() != null) return new Local<>(null, ctx.pending());   // suspension swallowed by model code
            if (ctx.used() != supplied.size())
                throw new IllegalStateException("Replay used " + ctx.used() + " of " + supplied.size()
                    + " supplied values; the departure is not deterministic in its state");
            int j2 = (kind == Kind.EXTERNAL || kind == Kind.CONFLUENT) ? parent.j + 1 : parent.j;
            X xi2 = spec.transducer.record(parent.xi, t, outputs);
            Object key = spec.merge == ParaSpec.Merge.SAFE ? StateKeys.of(model) : null;
            return new Local<>(new Branch<>(Snapshots.save(model), key, t, node.tN(), j2, parent.n + 1, xi2, w), null);
        }

        static void record(List<Message<?>> msgs, List<OutputEvent> into) {
            for (Message<?> m : msgs) into.add(new OutputEvent(m.getPort().getName(), m.getData()));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static MessageBag inputBag(AtomicModel model, InputTrace.Bundle b, double t) {
            MessageBag bag = new MessageBagImpl();
            bag.setMessageTime(t);
            for (int i = 0; i < b.ports.size(); i++) {
                Port port = model.getInputPort(b.ports.get(i));
                if (port == null) throw new IllegalArgumentException("Model " + model.getName() + " has no input port " + b.ports.get(i));
                bag.add(port, (Serializable) b.values.get(i));
            }
            return bag;
        }

        double nextTime(Branch<X> k) { return Math.min(k.tN, spec.trace.timeAt(k.j)); }

        StopStatus stopStatus(Branch<X> k) {
            if (k.n >= spec.horizon) return StopStatus.HORIZON;
            if (spec.acceptor != null && spec.acceptor.accept(k.xi, k.theta, k.n)) return StopStatus.ACCEPT;
            if (Double.isInfinite(k.tN) && k.j >= spec.trace.size()) return StopStatus.PASSIVE;
            return StopStatus.LIVE;
        }

        /** MM: replace equal complete-continuation keys by one record with the summed weight. */
        List<Branch<X>> merge(List<Branch<X>> children) {
            if (spec.merge == ParaSpec.Merge.NONE || children.size() < 2) return children;
            Map<List<Object>, Branch<X>> groups = new LinkedHashMap<>();
            for (Branch<X> k : children) {
                List<Object> key = java.util.Arrays.asList(k.stateKey, k.theta, k.j, k.n, k.xi);
                Branch<X> prev = groups.get(key);
                if (prev == null) groups.put(key, k);
                else { groups.put(key, prev.withWeight(prev.w + k.w)); result.stats.merged++; }
            }
            return new ArrayList<>(groups.values());
        }
    }
}
