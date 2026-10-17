package paradevs.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.model.AtomicModel;

import paradevs.Para;
import paradevs.ms4.ModelNode;
import paradevs.ms4.Snapshots;
import paradevs.ms4.StateKeys;

/**
 * Graph-mode planner for decisions ({@code Para.decide}) over an MS4 model: the forward half of
 * lookahead control and value/policy iteration. It expands each root to depth n in decisions,
 * merging nodes that reach the same model state at the same decision depth, and records edges
 * (weight, reward, elapsed time). The result is a {@link Plan}, on which backward passes compute
 * values: max at decisions (control), policy average (evaluation), and leaves from a batch hook.
 *
 * <p>Nodes and keys.
 * <ul>
 * <li>Chance node: a persistent state (a completed departure) at decision depth d, after e events
 * since the last decision. Key: (model state, d, e). Its next departure may make chance draws
 * (expanded by the selection rule) and end in further chance nodes or decision nodes.</li>
 * <li>Decision node: a departure suspended at decide(). Key: (pre-departure model state, kind,
 * the values already supplied in the departure, d). It is a leaf when d = n.</li>
 * </ul>
 * The key holds no accumulated reward: rewards sit on edges, so a node's value depends only on its
 * state and remaining depth. With the event count e in chance keys the graph is layered and
 * acyclic; decision keys drop e, so paths with different numbers of events still meet at their
 * next decision. Times enter the key as set by {@link PlanSpec.TimeKey}.
 *
 * <p>Nodes are processed in the order (d, chance before decision, e), which is topological: every
 * edge leads to a later node. A node is therefore expanded once, after every arrival into it, and
 * budget-based selection sees its full reach weight.
 *
 * <p>No input trace: the agent's decisions take the place of external inputs. Components of a
 * coupled model still exchange messages through their couplings.
 */
public final class Planner {
    private Planner() {}

    /** Where a plan starts. */
    public static final class Root {
        final Supplier<? extends AtomicModel> fresh;
        final AtomicModel snapshot;
        final double now;
        private Root(Supplier<? extends AtomicModel> fresh, AtomicModel snapshot, double now) {
            this.fresh = fresh; this.snapshot = snapshot; this.now = now;
        }
        /** A fresh, uninitialized model: the planner runs initialize() at time 0, then expands. */
        public static Root fresh(Supplier<? extends AtomicModel> model) { return new Root(Objects.requireNonNull(model), null, 0); }
        /**
         * A model already running (e.g. from a plain MS4 simulation, between events), at simulation
         * time {@code now}. It is copied, not modified.
         */
        public static Root snapshot(AtomicModel model, double now) { return new Root(null, Objects.requireNonNull(model), now); }
    }

    /** Expand from fresh models built by {@code models}. */
    public static Plan expandFresh(PlanSpec spec, List<? extends Supplier<? extends AtomicModel>> models) {
        List<Root> roots = new ArrayList<>();
        for (Supplier<? extends AtomicModel> m : models) roots.add(Root.fresh(m));
        return expand(spec, roots);
    }

    /** Expand the lookahead graph from every root (one shared, merged graph). */
    public static Plan expand(PlanSpec spec, List<Root> roots) {
        return new Builder(spec).run(roots);
    }

    // ------------------------------------------------------------------ expansion

    private static final Comparator<Plan.Node> ORDER = Comparator
        .<Plan.Node>comparingInt(u -> u.depth)
        .thenComparingInt(u -> u.isDecision() ? 1 : 0)
        .thenComparingInt(u -> u.events)
        .thenComparingInt(u -> u.id);

    private static final class Builder {
        final PlanSpec spec;
        final Random rng;
        final Plan.Stats stats = new Plan.Stats();
        final Map<Object, Plan.Node> table = new HashMap<>();
        final PriorityQueue<Plan.Node> queue = new PriorityQueue<>(ORDER);
        final List<Plan.Node> order = new ArrayList<>();
        int nextId = 0;

        Builder(PlanSpec spec) { this.spec = spec; this.rng = new Random(ParaEngine.Run.scramble(spec.seed)); }

        Plan run(List<Root> roots) {
            long start = System.currentTimeMillis();
            int[] rootIds = new int[roots.size()];
            for (int i = 0; i < roots.size(); i++) {
                Root root = roots.get(i);
                Plan.Node r = new Plan.Node(nextId++, Plan.Type.ROOT, 0, -1);
                r.reach = 1.0;
                r.status = Plan.Status.EXPANDED;
                List<Plan.Edge> edges = new ArrayList<>();
                r.out.add(edges);
                if (root.fresh != null) {
                    r.time = 0;
                    expandDeparture(r, Snapshots.save(root.fresh.get()), ParaEngine.Kind.INIT, 0.0, Collections.emptyList(), edges);
                } else {
                    r.time = root.now;
                    AtomicModel copy = Snapshots.restore(Snapshots.saveDetached(root.snapshot));
                    Plan.Node c = chanceNode(copy, root.now, ModelNode.of(copy).tN(), 0, 0);
                    edge(r, edges, c, 1.0, 0, 0);
                }
                order.add(r);
                rootIds[i] = r.id;
            }
            while (!queue.isEmpty()) {
                Plan.Node u = queue.poll();
                process(u);
                order.add(u);
            }
            stats.millis = System.currentTimeMillis() - start;
            return new Plan(spec, order, rootIds, stats);
        }

        void process(Plan.Node u) {
            if (!u.isDecision()) {
                if (Double.isInfinite(u.tN)) { u.status = Plan.Status.TERMINAL; stats.terminals++; u.state = null; return; }
                if (u.events >= spec.maxEventsPerEpoch)
                    throw new IllegalStateException("More than " + spec.maxEventsPerEpoch + " events without a decision; "
                        + "does the model call Para.decide? (raise PlanSpec.maxEventsPerEpoch if intended)");
                List<Plan.Edge> edges = new ArrayList<>();
                u.out.add(edges);
                expandDeparture(u, u.state, ParaEngine.Kind.INTERNAL, u.tN, Collections.emptyList(), edges);
                u.state = null;
                u.status = Plan.Status.EXPANDED;
                return;
            }
            if (u.depth >= spec.depth) { u.status = Plan.Status.LEAF; stats.leaves++; u.parentState = null; return; }
            int nA = u.options.size();
            u.expanded = new boolean[nA];
            double[] p = null;
            if (spec.prune != null && u.depth > 0) p = spec.prune.probabilities(u.label, u.obs, u.options);
            for (int a = 0; a < nA; a++) {
                List<Plan.Edge> edges = new ArrayList<>();
                u.out.add(edges);
                if (p != null && !(p[a] > 0)) continue;
                u.expanded[a] = true;
                List<Object> sup = new ArrayList<>(u.supplied);
                sup.add(u.options.get(a));
                expandDeparture(u, u.parentState, u.kind, u.time, sup, edges);
            }
            u.parentState = null;
            u.status = Plan.Status.EXPANDED;
        }

        /** One work item of a departure: the values supplied so far, its weight, and its decision depth. */
        static final class Item {
            final List<Object> supplied; final double w; final int depth;
            Item(List<Object> supplied, double w, int depth) { this.supplied = supplied; this.w = w; this.depth = depth; }
        }

        /**
         * Run a departure from {@code parent} with {@code prefix} supplied, through its chance draws,
         * adding an edge from {@code src} to each resulting chance or decision node.
         */
        void expandDeparture(Plan.Node src, byte[] parent, ParaEngine.Kind kind, double t, List<Object> prefix, List<Plan.Edge> edges) {
            int d0 = src.isDecision() ? src.depth + 1 : src.depth;
            double dt = t - src.time;
            Object parentKey = null;   // computed once, when a decision suspends
            List<Item> work = new ArrayList<>();
            work.add(new Item(prefix, 1.0, d0));
            while (!work.isEmpty()) {
                Item it = work.remove(work.size() - 1);
                Local loc = runLocal(parent, kind, t, it.supplied);
                if (loc.pending == null) {
                    int events = it.depth == src.depth && !src.isDecision() ? src.events + 1 : 0;
                    Plan.Node c = chanceNode(loc.model, t, loc.tN, it.depth, events);
                    double r = spec.reward.reward(t, loc.outputs);
                    if (r != 0 && dt != 0) r *= Math.pow(spec.gamma, dt);
                    edge(src, edges, c, it.w, r, dt);
                } else if (loc.pending.decision) {
                    if (parentKey == null) parentKey = timeKey(Snapshots.restore(parent), t);
                    Plan.Node c = decisionNode(parentKey, parent, kind, t, it.supplied, loc.pending, it.depth);
                    edge(src, edges, c, it.w, 0, dt);
                } else {
                    SelectionRule.Options opts = spec.selection.select(loc.pending.label, loc.pending.law, rng, src.reach * it.w);
                    stats.choiceNodes++;
                    stats.residual += src.reach * it.w * opts.omitted;
                    for (int i = 0; i < opts.values.size(); i++) {
                        double alpha = opts.factors.get(i);
                        if (!(alpha > 0)) continue;
                        List<Object> sup = new ArrayList<>(it.supplied);
                        sup.add(opts.values.get(i));
                        work.add(new Item(sup, it.w * alpha, it.depth));
                    }
                }
            }
        }

        void edge(Plan.Node src, List<Plan.Edge> edges, Plan.Node child, double w, double r, double dt) {
            edges.add(new Plan.Edge(child.id, w, r, dt));
            child.reach += src.reach * w;
            stats.edges++;
        }

        Object timeKey(AtomicModel m, double t) {
            return spec.timeKey == PlanSpec.TimeKey.RELATIVE ? StateKeys.relative(m, t) : Arrays.asList(StateKeys.of(m), t);
        }

        Plan.Node chanceNode(AtomicModel model, double time, double tN, int depth, int events) {
            Object key = spec.merge ? Arrays.asList("C", timeKey(model, time), depth, events) : new Object();
            Plan.Node u = table.get(key);
            if (u != null) { stats.merged++; return u; }
            u = new Plan.Node(nextId++, Plan.Type.CHANCE, depth, events);
            u.state = Snapshots.save(model);   // serialized only when the state is new
            u.time = time; u.tN = tN;
            add(key, u);
            stats.chanceNodes++;
            return u;
        }

        Plan.Node decisionNode(Object parentKey, byte[] parent, ParaEngine.Kind kind, double t, List<Object> supplied,
                               Para.Pending pending, int depth) {
            Object key = spec.merge ? Arrays.asList("D", parentKey, kind, supplied, depth) : new Object();
            Plan.Node u = table.get(key);
            if (u != null) { stats.merged++; return u; }
            u = new Plan.Node(nextId++, Plan.Type.DECISION, depth, 0);
            u.parentState = parent; u.kind = kind; u.time = t; u.supplied = supplied;
            u.label = pending.label; u.obs = pending.obs; u.options = pending.options;
            add(key, u);
            stats.decisionNodes++;
            return u;
        }

        void add(Object key, Plan.Node u) {
            if (spec.merge) table.put(key, u);
            queue.add(u);
            if (stats.nodes() + 1 > spec.maxNodes)
                throw new IllegalStateException("Plan exceeds maxNodes=" + spec.maxNodes + "; lower the depth or merge");
        }

        static final class Local {
            final AtomicModel model; final double tN; final List<OutputEvent> outputs; final Para.Pending pending;
            Local(AtomicModel model, double tN, List<OutputEvent> outputs, Para.Pending pending) {
                this.model = model; this.tN = tN; this.outputs = outputs; this.pending = pending;
            }
        }

        /** RunLocal under the planning context: decide() suspends as a decision. */
        Local runLocal(byte[] parent, ParaEngine.Kind kind, double t, List<Object> supplied) {
            stats.departures++;
            AtomicModel model = Snapshots.restore(parent);
            ModelNode node = ModelNode.of(model);
            List<OutputEvent> outputs = new ArrayList<>();
            Para.DrawContext ctx = Para.enterPlanning(supplied);
            try {
                if (kind == ParaEngine.Kind.INIT) node.init(t);
                else {
                    ParaEngine.Run.record(node.output(t), outputs);
                    node.transition(t, MessageBag.EMPTY);
                }
            } catch (Throwable th) {
                if (ctx.pending() != null) return new Local(null, 0, null, ctx.pending());
                throw new IllegalStateException("Model failed during " + kind + " at t=" + t + ": " + th, th);
            } finally {
                Para.exit();
            }
            if (ctx.pending() != null) return new Local(null, 0, null, ctx.pending());
            if (ctx.used() != supplied.size())
                throw new IllegalStateException("Replay used " + ctx.used() + " of " + supplied.size()
                    + " supplied values; the departure is not deterministic in its state");
            return new Local(model, node.tN(), outputs, null);
        }
    }

    // ------------------------------------------------------------------ acting

    /**
     * A {@link Para.Policy} that acts by lookahead ("act" mode): at each decide() it plans from
     * {@code rootAt(obs)}, a fresh model whose first decision is the current one, and returns
     * epsilon-greedy probabilities over Q(obs, a) (ties share the greedy mass). Install it with
     * {@code Para.setPolicy} to run an agent under plain MS4 simulation.
     *
     * The observation must be a Markov state, since the plan restarts from it. With exhaustive
     * selection and fixed leaf values the plan for an observation never changes, so results are
     * cached by observation; {@link #prepare} fills the cache for many observations in one batched plan.
     */
    public static final class LookaheadPolicy implements Para.Policy {
        final PlanSpec spec; final Function<Object, ? extends AtomicModel> rootAt;
        final DecisionRule rule; final LeafValues leaf; final double epsilon; final boolean cache;
        final Map<Object, double[]> qCache = new ConcurrentHashMap<>();
        public long plans, departures;

        LookaheadPolicy(PlanSpec spec, Function<Object, ? extends AtomicModel> rootAt, DecisionRule rule, LeafValues leaf,
                        double epsilon, boolean cache) {
            this.spec = spec; this.rootAt = rootAt; this.rule = rule; this.leaf = leaf; this.epsilon = epsilon; this.cache = cache;
        }

        /** Plan every observation in {@code obs} in one shared graph and cache their action values. */
        public LookaheadPolicy prepare(List<?> obs) {
            List<Root> roots = new ArrayList<>();
            for (Object o : obs) roots.add(Root.fresh(() -> rootAt.apply(o)));
            Plan plan = expand(spec, roots);
            plans++; departures += plan.stats().departures;
            Plan.Values v = plan.backup(rule, leaf);
            for (int i = 0; i < obs.size(); i++) if (v.hasDecision(i)) qCache.put(obs.get(i), v.q(i));
            return this;
        }

        /** Q(obs, .) by lookahead (cached when enabled). */
        public double[] q(Object obs) {
            double[] q = cache ? qCache.get(obs) : null;
            if (q != null) return q;
            Plan plan = expand(spec, Collections.singletonList(Root.fresh(() -> rootAt.apply(obs))));
            plans++; departures += plan.stats().departures;
            q = plan.backup(rule, leaf).q(0);
            if (cache) qCache.put(obs, q);
            return q;
        }

        @Override public double[] probabilities(String label, Object obs, List<?> options) {
            double[] q = q(obs);
            if (q.length != options.size())
                throw new IllegalStateException("Plan from obs " + obs + " has " + q.length + " options, decide() has " + options.size());
            double best = Double.NEGATIVE_INFINITY;
            for (double x : q) if (!Double.isNaN(x)) best = Math.max(best, x);
            int ties = 0;
            for (double x : q) if (!Double.isNaN(x) && x >= best - 1e-12) ties++;
            double[] p = new double[q.length];
            for (int a = 0; a < q.length; a++)
                p[a] = epsilon / q.length + (!Double.isNaN(q[a]) && q[a] >= best - 1e-12 ? (1 - epsilon) / ties : 0);
            return p;
        }
    }

    /** See {@link LookaheadPolicy}. Use {@code DecisionRule.max()} for control. */
    public static LookaheadPolicy lookahead(PlanSpec spec, Function<Object, ? extends AtomicModel> rootAt, DecisionRule rule,
                                            LeafValues leaf, double epsilon, boolean cache) {
        return new LookaheadPolicy(spec, rootAt, rule, leaf, epsilon, cache);
    }
}
