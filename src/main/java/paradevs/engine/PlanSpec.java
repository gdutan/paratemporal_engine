package paradevs.engine;

import java.util.List;
import java.util.Objects;

import paradevs.Para;

/**
 * Configuration of a graph-mode plan ({@link Planner}): lookahead depth in decisions, discount,
 * selection rule at chance nodes, merging, the time convention of the merge key, and the reward
 * read from the model's outputs. See docs/rl/ParaDEVS_Control_Spec.pdf.
 *
 * <ul>
 * <li>Depth n counts decisions ({@code Para.decide} calls), not DEVS events. A decision node
 * reached after n decisions is a leaf and takes its value from the leaf-value hook.</li>
 * <li>Discounting is by simulation time: a reward at time t counts gamma^(t - t0) from a node
 * at time t0 (a semi-MDP). With one time unit per decision this is the usual gamma^k.</li>
 * </ul>
 */
public final class PlanSpec {

    /**
     * Which time information enters the merge key.
     * <ul>
     * <li>ABSOLUTE (default): the key holds the absolute node time and the model's absolute event
     * times. Sound for every model.</li>
     * <li>RELATIVE: the key holds times relative to the node time (time to the next event, and the
     * elapsed time of coupled components). Sound for time-homogeneous models, whose transitions do
     * not read the absolute simulation time except through model fields (fields are always in the
     * key). It lets states reached at different times merge, which matters with random event times.</li>
     * </ul>
     */
    public enum TimeKey { ABSOLUTE, RELATIVE }

    /** The reward carried by one departure, read from its outputs (all at time t). */
    @FunctionalInterface
    public interface Reward {
        double reward(double t, List<OutputEvent> outputs);

        /** Sum of the numeric values emitted on {@code port}. */
        static Reward port(String port) {
            return (t, outs) -> {
                double r = 0;
                for (OutputEvent e : outs)
                    if (e.port.equals(port) && e.value instanceof Number) r += ((Number) e.value).doubleValue();
                return r;
            };
        }
    }

    final int depth;
    double gamma = 1.0;
    SelectionRule selection = SelectionRule.exhaustive();
    boolean merge = true;
    TimeKey timeKey = TimeKey.ABSOLUTE;
    Reward reward = Reward.port("reward");
    Para.Policy prune = null;
    long seed = 1L;
    int maxEventsPerEpoch = 100_000;
    long maxNodes = 2_000_000L;

    private PlanSpec(int depth) {
        if (depth < 0) throw new IllegalArgumentException("depth >= 0");
        this.depth = depth;
    }

    /** A plan looking {@code n} decisions ahead (n = 0: the root decision itself is the leaf). */
    public static PlanSpec depth(int n) { return new PlanSpec(n); }

    /** Discount per unit of simulation time, 0 < gamma <= 1. */
    public PlanSpec gamma(double g) {
        if (!(g > 0 && g <= 1)) throw new IllegalArgumentException("0 < gamma <= 1");
        gamma = g; return this;
    }
    /** Rule at chance nodes: exhaustive (exact), monteCarlo(k), budget(B), hybrid by label, ... */
    public PlanSpec selection(SelectionRule s) { selection = Objects.requireNonNull(s); return this; }
    /** Merge nodes that reach the same state at the same decision depth (default on). Off gives the plain tree. */
    public PlanSpec merge(boolean on) { merge = on; return this; }
    public PlanSpec timeKey(TimeKey k) { timeKey = Objects.requireNonNull(k); return this; }
    public PlanSpec reward(Reward r) { reward = Objects.requireNonNull(r); return this; }
    /**
     * Optional: below the root decisions, expand only the actions this policy gives positive
     * probability. Saves work for policy evaluation; a max backup then ranges over those actions only.
     */
    public PlanSpec prune(Para.Policy p) { prune = p; return this; }
    public PlanSpec seed(long s) { seed = s; return this; }
    /** Safety cap on DEVS events between two decisions on one path. */
    public PlanSpec maxEventsPerEpoch(int cap) { maxEventsPerEpoch = cap; return this; }
    /** Safety cap on graph nodes. */
    public PlanSpec maxNodes(long cap) { maxNodes = cap; return this; }

    public int depth() { return depth; }
    public double gamma() { return gamma; }
}
