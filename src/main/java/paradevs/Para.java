package paradevs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * The only call a DEVS model needs to become paratemporal-executable. Any random value in a
 * transition, output, or initialize() is obtained through {@link #choose(String, Law)}
 * instead of a random number generator.
 *
 * <ul>
 * <li><b>Under ordinary MS4 simulation</b> (no engine active), choose() samples from the law,
 * so the model still runs as a normal stochastic DEVS model in MS4 Me.</li>
 * <li><b>Under the paratemporal engine</b>, choose() is the engine-controlled random generator
 * of the derandomized departure (Paratemporal_Adaptation, Def. "Derandomized departure"). The
 * engine supplies the values v1*,...,vN* of the departure in evaluation order. When the next
 * value has not been supplied yet, the departure suspends at that choice point and the engine
 * expands it. A degenerate law never suspends.</li>
 * </ul>
 *
 * Requirement on models: given the persistent state, the departure arguments, and the supplied
 * values, a departure must be deterministic. In particular, it must request the same draws with
 * the same laws in the same order. Do not use other sources of randomness.
 */
public final class Para {
    private Para() {}

    private static final ThreadLocal<DrawContext> CONTEXT = new ThreadLocal<>();
    private static Random defaultRandom = new Random();

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

    // ---------------------------------------------------------------- engine side

    /** Install a context supplying {@code supplied} values; returns it for inspection. */
    public static DrawContext enter(List<Object> supplied) {
        DrawContext ctx = new DrawContext(supplied);
        CONTEXT.set(ctx);
        return ctx;
    }

    public static void exit() { CONTEXT.remove(); }

    /** The pending choice point when a departure suspends. */
    public static final class Pending {
        public final String label;
        public final Law<?> law;
        Pending(String label, Law<?> law) { this.label = label; this.law = law; }
    }

    /** Thrown inside a departure to suspend it at a choice point. Models must not catch it. */
    public static final class Suspension extends Error {
        private static final long serialVersionUID = 1L;
        Suspension() { super("paratemporal suspension", null, false, false); }
    }

    /** Per-departure draw bookkeeping: supplied values, draws consumed, and any pending draw. */
    public static final class DrawContext {
        private final List<Object> supplied;
        private int used = 0;
        private Pending pending;
        private final List<String> labels = new ArrayList<>();

        DrawContext(List<Object> supplied) { this.supplied = supplied; }

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

        /** The suspended choice point, or null if the departure completed. */
        public Pending pending() { return pending; }
        /** Number of supplied values actually consumed. */
        public int used() { return used; }
    }
}
