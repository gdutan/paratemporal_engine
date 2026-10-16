package paradevs.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A graph-mode plan: the expanded lookahead graph from one or more roots ({@link Planner#expand}),
 * on which any number of backward passes can be run. The forward expansion (simulation) is the
 * expensive part and is done once; a backward pass is a cheap sweep over the edges, so a plan
 * can be re-valued with new leaf values (value iteration, policy evaluation) without simulating
 * again.
 *
 * <pre>
 *   chance node   V(u) = sum_e w_e (r_e + gamma^dt_e V(child_e))
 *   decision node Q(u,a) = sum_{e in a} w_e (r_e + gamma^dt_e V(child_e)),  V(u) = rule(Q(u,.))
 *   leaf          V(u) = hook(obs_u)  (a decision after n decisions);  terminal: V = 0
 * </pre>
 */
public final class Plan {

    enum Type { ROOT, CHANCE, DECISION }
    enum Status { OPEN, EXPANDED, LEAF, TERMINAL }

    /** One edge: weight (probability or sample factor), discounted reward, elapsed time. */
    static final class Edge {
        final int child; final double w, r, dt;
        Edge(int child, double w, double r, double dt) { this.child = child; this.w = w; this.r = r; this.dt = dt; }
    }

    static final class Node {
        final int id;
        final Type type;
        final int depth, events;
        Status status = Status.OPEN;
        double reach;
        // chance (persistent) nodes
        byte[] state; double time, tN;
        // decision nodes: the suspended departure and its decision
        byte[] parentState; ParaEngine.Kind kind; List<Object> supplied;
        String label; Object obs; List<?> options;
        boolean[] expanded;
        /** Outgoing edges: one list for chance/root nodes, one list per option for decisions. */
        List<List<Edge>> out = new ArrayList<>();
        Node(int id, Type type, int depth, int events) { this.id = id; this.type = type; this.depth = depth; this.events = events; }
        boolean isDecision() { return type == Type.DECISION; }
    }

    /** Work and size counters. */
    public static final class Stats {
        public long departures;      // departure runs (simulation work), including replays
        public long choiceNodes;     // chance draws expanded
        public long chanceNodes;     // persistent-state nodes created
        public long decisionNodes;   // decision nodes created (including leaves)
        public long leaves;          // decision nodes at depth n
        public long terminals;       // passive branches
        public long edges;
        public long merged;          // arrivals at an existing node (merge hits)
        public double residual;      // probability mass omitted by partial selection rules
        public long millis;
        public long nodes() { return chanceNodes + decisionNodes; }
        @Override public String toString() {
            return "departures=" + departures + " nodes=" + nodes() + " (chance " + chanceNodes + ", decision " + decisionNodes
                + ", leaves " + leaves + ", terminal " + terminals + ") edges=" + edges + " merged=" + merged
                + " choiceNodes=" + choiceNodes + " ms=" + millis;
        }
    }

    final PlanSpec spec;
    final List<Node> nodes;          // in processing (topological) order; roots first
    final int[] roots;
    final int[] rootDecision;        // first decision reached with certainty from each root, or -1
    final double[] rootChainR, rootChainDisc;
    final Stats stats;
    private final List<Object> leafObs = new ArrayList<>();
    private final int[] leafSlot;    // per node: index into leafObs, or -1

    Plan(PlanSpec spec, List<Node> nodes, int[] roots, Stats stats) {
        this.spec = spec; this.nodes = nodes; this.roots = roots; this.stats = stats;
        int maxId = 0;
        for (Node u : nodes) maxId = Math.max(maxId, u.id);
        leafSlot = new int[maxId + 1];
        java.util.Arrays.fill(leafSlot, -1);
        Map<Object, Integer> slots = new LinkedHashMap<>();
        for (Node u : nodes)
            if (u.status == Status.LEAF) {
                Integer k = slots.get(u.obs);
                if (k == null) { k = leafObs.size(); slots.put(u.obs, k); leafObs.add(u.obs); }
                leafSlot[u.id] = k;
            }
        rootDecision = new int[roots.length];
        rootChainR = new double[roots.length];
        rootChainDisc = new double[roots.length];
        Node[] byId = byId();
        for (int i = 0; i < roots.length; i++) {
            Node u = byId[roots[i]];
            double r = 0, disc = 1;
            while (u != null && !u.isDecision()) {
                if (u.out.isEmpty() || u.out.get(0).size() != 1 || Math.abs(u.out.get(0).get(0).w - 1) > 1e-12) { u = null; break; }
                Edge e = u.out.get(0).get(0);
                r += disc * e.r; disc *= Math.pow(spec.gamma, e.dt);
                u = byId[e.child];
            }
            rootDecision[i] = u == null ? -1 : u.id;
            rootChainR[i] = r; rootChainDisc[i] = disc;
        }
    }

    private Node[] byIdCache;
    private Node[] byId() {
        if (byIdCache == null) {
            int maxId = 0;
            for (Node u : nodes) maxId = Math.max(maxId, u.id);
            byIdCache = new Node[maxId + 1];
            for (Node u : nodes) byIdCache[u.id] = u;
        }
        return byIdCache;
    }

    public int roots() { return roots.length; }
    public Stats stats() { return stats; }
    public PlanSpec spec() { return spec; }

    /** The distinct observations of the leaves, in a fixed order: the input of a batch leaf evaluation. */
    public List<Object> leafObservations() { return Collections.unmodifiableList(leafObs); }

    /** Backward pass with the leaf-value hook, called once with every leaf observation. */
    public Values backup(DecisionRule rule, LeafValues leaf) {
        double[] lv = leafObs.isEmpty() ? new double[0] : leaf.values(leafObservations());
        return backup(rule, lv);
    }

    /** Backward pass with leaf values aligned with {@link #leafObservations()}. */
    public Values backup(DecisionRule rule, double[] leafValues) {
        if (leafValues.length != leafObs.size())
            throw new IllegalArgumentException(leafValues.length + " leaf values for " + leafObs.size() + " leaf observations");
        Node[] byId = byId();
        double[] v = new double[byId.length];
        double[][] q = new double[byId.length][];
        double g = spec.gamma;
        for (int k = nodes.size() - 1; k >= 0; k--) {
            Node u = nodes.get(k);
            switch (u.status) {
                case LEAF: v[u.id] = leafValues[leafSlot[u.id]]; break;
                case TERMINAL: v[u.id] = 0; break;
                case OPEN: throw new IllegalStateException("node " + u.id + " was never expanded");
                case EXPANDED:
                    if (u.isDecision()) {
                        double[] qa = new double[u.options.size()];
                        for (int a = 0; a < qa.length; a++) qa[a] = u.expanded[a] ? sum(u.out.get(a), v, g) : Double.NaN;
                        q[u.id] = qa;
                        v[u.id] = rule.combine(u.label, u.obs, u.options, qa, u.expanded);
                    } else {
                        v[u.id] = sum(u.out.get(0), v, g);
                    }
                    break;
            }
        }
        return new Values(v, q);
    }

    private static double sum(List<Edge> edges, double[] v, double g) {
        double s = 0;
        for (Edge e : edges) s += e.w * (e.r + (g == 1 || e.dt == 0 ? 1 : Math.pow(g, e.dt)) * v[e.child]);
        return s;
    }

    /** The result of one backward pass. Root indices follow the order of the roots given to the planner. */
    public final class Values {
        private final double[] v;
        private final double[][] q;
        Values(double[] v, double[][] q) { this.v = v; this.q = q; }

        /** V(root i): the expected discounted return from the root, with the rule at every decision. */
        public double value(int i) { return v[roots[i]]; }

        /** Values of all roots. */
        public double[] values() { double[] out = new double[roots.length]; for (int i = 0; i < out.length; i++) out[i] = value(i); return out; }

        /** True when root i reaches one decision with certainty (the decision epoch Q is reported for). */
        public boolean hasDecision(int i) { return rootDecision[i] >= 0 && byId()[rootDecision[i]].status == Status.EXPANDED; }

        private Node decision(int i) {
            if (!hasDecision(i)) throw new IllegalStateException("Root " + i + " has no expanded first decision (terminal, "
                + "a chance draw before the first decision, or depth 0)");
            return byId()[rootDecision[i]];
        }

        /** Q(d, a) at root i's first decision d, per option (in the model's option order), measured from the decision time. */
        public double[] q(int i) { return q[decision(i).id].clone(); }
        public List<?> actions(int i) { return decision(i).options; }
        public Object obs(int i) { return decision(i).obs; }

        /** Index of the best action at root i; ties go to the lowest index. */
        public int greedyIndex(int i) {
            double[] qa = q(i); int best = -1;
            for (int a = 0; a < qa.length; a++) if (!Double.isNaN(qa[a]) && (best < 0 || qa[a] > qa[best])) best = a;
            return best;
        }
        public Object greedy(int i) { return actions(i).get(greedyIndex(i)); }

        /**
         * Epsilon-greedy probabilities over root i's options: 1 - epsilon shared by the actions within
         * {@code tieTol} of the best, epsilon spread uniformly over all options.
         */
        public double[] epsilonGreedy(int i, double epsilon, double tieTol) {
            double[] qa = q(i);
            double best = Double.NEGATIVE_INFINITY;
            for (double x : qa) if (!Double.isNaN(x)) best = Math.max(best, x);
            int ties = 0;
            for (double x : qa) if (!Double.isNaN(x) && x >= best - tieTol) ties++;
            double[] p = new double[qa.length];
            for (int a = 0; a < qa.length; a++)
                p[a] = epsilon / qa.length + (!Double.isNaN(qa[a]) && qa[a] >= best - tieTol ? (1 - epsilon) / ties : 0);
            return p;
        }
    }
}
