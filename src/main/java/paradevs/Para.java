package paradevs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * The only calls a DEVS model needs to become paratemporal-executable. Any random value in a
 * transition, output, or initialize() is obtained through {@link #choose(String, Law)}
 * instead of a random number generator, and any agent decision through
 * {@link #decide(String, Object, List)}.
 *
 * <ul>
 * <li><b>Under ordinary MS4 simulation</b> (no engine active), choose() samples from the law,
 * so the model still runs as a normal stochastic DEVS model in MS4 Me. decide() samples from the
 * installed {@link Policy} ({@link #setPolicy}); the default policy is uniform over the options.</li>
 * <li><b>Under the paratemporal engine</b>, choose() is the engine-controlled random generator
 * of the derandomized departure (Paratemporal_Adaptation, Def. "Derandomized departure"). The
 * engine supplies the values v1*,...,vN* of the departure in evaluation order. When the next
 * value has not been supplied yet, the departure suspends at that choice point and the engine
 * expands it. A degenerate law never suspends. decide() is a draw from the installed policy's
 * law (policy evaluation over the full return distribution).</li>
 * <li><b>Under the graph planner</b> ({@code paradevs.engine.Planner}), decide() suspends as a
 * decision node: the planner tries every option and backs up a max (control), a policy average
 * (evaluation), or the value of each root action.</li>
 * </ul>
 *
 * Requirement on models: given the persistent state, the departure arguments, and the supplied
 * values, a departure must be deterministic. In particular, it must request the same draws and
 * decisions with the same laws and options in the same order. Do not use other sources of
 * randomness.
 */
public final class Para {
    private Para() {}

    private static final ThreadLocal<DrawContext> CONTEXT = new ThreadLocal<>();
    private static Random defaultRandom = new Random();
    private static volatile Policy policy = Policy.UNIFORM;

    /** Seed or replace the generator used when no engine is active. */
    public static void setDefaultRandom(Random rng) { defaultRandom = Objects.requireNonNull(rng); }

    /** Draw a value from {@code law}. {@code label} names the primitive (e.g. "phase", "reward"). */
    public static <T> T choose(String label, Law<T> law) {
        DrawContext ctx = CONTEXT.get();
        if (ctx == null) return law.sample(defaultRandom);
        return ctx.next(label, law);
    }

    /** Unlabelled draw. */
    public static <T> T choose(Law<T> law) { return choose("draw", law); }

    // ---------------------------------------------------------------- decisions

    /** The "do nothing" option. A model may list it among its options; choosing it is still one decision. */
    public enum NoOp { NOOP }
    public static final NoOp NOOP = NoOp.NOOP;

    /**
     * A stochastic policy pi(.|obs) over the options of a decision. It returns one probability per
     * option, in the order of {@code options}, summing to 1. Implementations may come from Python
     * (JPype implements Java interfaces), so the signature uses plain arrays.
     */
    @FunctionalInterface
    public interface Policy {
        double[] probabilities(String label, Object obs, List<?> options);

        /** Uniform over the options: the default under plain MS4 simulation and the forward engine. */
        Policy UNIFORM = (label, obs, options) -> {
            double[] p = new double[options.size()];
            Arrays.fill(p, 1.0 / options.size());
            return p;
        };

        /** Always the first option (a deterministic default). */
        Policy FIRST = (label, obs, options) -> {
            double[] p = new double[options.size()];
            p[0] = 1;
            return p;
        };
    }

    /** Install the policy used by decide() under plain MS4 simulation and under the forward engine. */
    public static void setPolicy(Policy p) { policy = Objects.requireNonNull(p); }
    public static Policy policy() { return policy; }

    /**
     * One agent decision, one RL step. {@code obs} is what the agent observes (the policy's input
     * and the planner's leaf-value input); it must be a function of the model state, immutable and
     * with equals/hashCode. {@code options} are the available actions, possibly including
     * {@link #NOOP}; they must be a function of the state, distinct, and listed in a fixed order.
     *
     * <ul>
     * <li>No engine: a sample from the installed policy.</li>
     * <li>Forward engine (ParaEngine): a draw labelled {@code label} from the policy's law.</li>
     * <li>Graph planner: a decision node (the planner supplies the action).</li>
     * </ul>
     */
    public static <A> A decide(String label, Object obs, List<A> options) {
        if (options == null || options.isEmpty()) throw new IllegalArgumentException("decide() needs at least one option");
        DrawContext ctx = CONTEXT.get();
        if (ctx != null && ctx.planning) return ctx.nextDecision(label, obs, options);
        Law<A> law = policyLaw(policy, label, obs, options);
        if (ctx == null) return law.sample(defaultRandom);
        return ctx.next(label, law);
    }

    /** A decision without an observation (state-independent policies only). */
    public static <A> A decide(String label, List<A> options) { return decide(label, null, options); }

    /** The law pi(.|obs) over {@code options}. */
    public static <A> Law<A> policyLaw(Policy pi, String label, Object obs, List<A> options) {
        double[] p = pi.probabilities(label, obs, options);
        if (p == null || p.length != options.size())
            throw new IllegalStateException("Policy returned " + (p == null ? "null" : p.length + " probabilities")
                + " for " + options.size() + " options at decision '" + label + "'");
        return Law.of(options, p);
    }

    // ---------------------------------------------------------------- engine side

    /** Install a context supplying {@code supplied} values; returns it for inspection. Contexts nest. */
    public static DrawContext enter(List<Object> supplied) { return install(new DrawContext(supplied, false)); }

    /** Like {@link #enter}, but decide() suspends as a decision node (the graph planner's context). */
    public static DrawContext enterPlanning(List<Object> supplied) { return install(new DrawContext(supplied, true)); }

    private static DrawContext install(DrawContext ctx) {
        ctx.outer = CONTEXT.get();
        CONTEXT.set(ctx);
        return ctx;
    }

    /** Leave the current context and restore the enclosing one, if any (a planning policy may plan inside a run). */
    public static void exit() {
        DrawContext ctx = CONTEXT.get();
        if (ctx != null && ctx.outer != null) CONTEXT.set(ctx.outer); else CONTEXT.remove();
    }

    /** The pending choice point when a departure suspends: a draw, or (under the planner) a decision. */
    public static final class Pending {
        public final String label;
        /** The law of a draw; null for a decision. */
        public final Law<?> law;
        public final boolean decision;
        /** Decisions only: the observation and the options. */
        public final Object obs;
        public final List<?> options;
        Pending(String label, Law<?> law) { this.label = label; this.law = law; this.decision = false; this.obs = null; this.options = null; }
        Pending(String label, Object obs, List<?> options) {
            this.label = label; this.law = null; this.decision = true; this.obs = obs;
            this.options = Collections.unmodifiableList(new ArrayList<>(options));
        }
    }

    /** Thrown inside a departure to suspend it at a choice point. Models must not catch it. */
    public static final class Suspension extends Error {
        private static final long serialVersionUID = 1L;
        Suspension() { super("paratemporal suspension", null, false, false); }
    }

    /** Per-departure draw bookkeeping: supplied values, draws consumed, and any pending draw. */
    public static final class DrawContext {
        private final List<Object> supplied;
        private final boolean planning;
        private DrawContext outer;
        private int used = 0;
        private Pending pending;
        private final List<String> labels = new ArrayList<>();

        DrawContext(List<Object> supplied, boolean planning) { this.supplied = supplied; this.planning = planning; }

        @SuppressWarnings("unchecked")
        <T> T next(String label, Law<T> law) {
            if (pending != null)
                throw new IllegalStateException("choose() called after a suspension; do not catch Para.Suspension");
            if (law.isDegenerate()) return law.atoms().get(0).value;   // RunLocal passes through
            if (used < supplied.size()) {
                Object v = supplied.get(used++);
                if (law.prob(v) <= 0)
                    throw new IllegalStateException("Replay mismatch at draw '" + label + "': value " + v
                        + " is not in " + law + ". The departure is not deterministic in its state.");
                labels.add(label);
                return (T) v;
            }
            pending = new Pending(label, law);
            throw new Suspension();
        }

        /** A decision under the planner: replay the supplied action, or suspend. Never skipped, even with one option. */
        @SuppressWarnings("unchecked")
        <A> A nextDecision(String label, Object obs, List<A> options) {
            if (pending != null)
                throw new IllegalStateException("decide() called after a suspension; do not catch Para.Suspension");
            if (used < supplied.size()) {
                Object v = supplied.get(used++);
                if (!options.contains(v))
                    throw new IllegalStateException("Replay mismatch at decision '" + label + "': action " + v
                        + " is not among " + options + ". The departure is not deterministic in its state.");
                labels.add(label);
                return (A) v;
            }
            pending = new Pending(label, obs, options);
            throw new Suspension();
        }

        /** The suspended choice point, or null if the departure completed. */
        public Pending pending() { return pending; }
        /** Number of supplied values actually consumed. */
        public int used() { return used; }
    }
}
