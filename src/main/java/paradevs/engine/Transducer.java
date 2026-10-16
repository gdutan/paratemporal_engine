package paradevs.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The experimental frame's transducer: folds the physical outputs of each completed departure
 * into the recorded observation xi (main_paratemporal, Section 2.3).
 *
 * xi is part of the merge key, so it must be immutable and implement equals/hashCode. Keep it
 * to a sufficient summary of what the result Phi needs (e.g. a running total rather than the
 * full history), and more branches will merge.
 */
public interface Transducer<X> {
    X initial();

    /** Called once per completed departure at time t; {@code outputs} may be empty. */
    X record(X xi, double t, List<OutputEvent> outputs);

    /**
     * A transducer whose observation splits into an additive accumulated reward G and the rest,
     * xi = (G, rest). Declaring the split is what {@link ParaSpec.Merge#EXPECTATION} requires: it
     * joins branches whose continuation (state key, theta, j, n, rest) is identical and gives the
     * joined branch the weight-averaged reward sum(w G) / sum(w). By linearity the expectation of
     * any statistic affine in G is then exact; the return distribution is not.
     *
     * <p>Contract (the engine trusts it; it cannot check it):
     * <ol>
     * <li>{@code reward(withReward(xi, r)) == r}, and {@code withReward(xi, reward(xi))} equals xi.</li>
     * <li>The reward is additive and inert: {@link #record} adds an increment that does not depend
     *   on the reward so far, and the rest of the new observation does not depend on it either.
     *   Formally, for every r: {@code record(withReward(xi, r), t, o)} equals
     *   {@code withReward(record(xi, t, o), r + reward(record(xi, t, o)) - reward(xi))}.</li>
     * <li>Nothing else reads the reward to decide the future: the acceptor, if any, must ignore it.</li>
     * <li>{@code equals} compares the reward as a double, so {@code withReward(xi, 0.0)} is the
     *   canonical "reward removed" form used in the merge key.</li>
     * </ol>
     * Discounting is allowed when the discount factor is part of the rest (e.g. gamma^steps), since
     * the increment may depend on the rest. A transducer implementing this interface works the same
     * as any other under {@code Merge.NONE} and {@code Merge.SAFE}.
     */
    interface Additive<X> extends Transducer<X> {
        /** The accumulated additive reward G carried by xi. */
        double reward(X xi);

        /** xi with its reward replaced by r and everything else unchanged. */
        X withReward(X xi, double r);
    }

    /** The full timestamped output record. */
    static Transducer<History> history() {
        return new Transducer<History>() {
            @Override public History initial() { return History.EMPTY; }
            @Override public History record(History xi, double t, List<OutputEvent> outs) {
                return outs.isEmpty() ? xi : xi.append(t, outs);
            }
        };
    }

    /** An immutable list of (time, output) observations. */
    final class History {
        public static final History EMPTY = new History(Collections.emptyList());

        public static final class Entry {
            public final double time;
            public final OutputEvent output;
            Entry(double time, OutputEvent output) { this.time = time; this.output = output; }
            @Override public boolean equals(Object o) {
                return o instanceof Entry && Double.compare(time, ((Entry) o).time) == 0 && output.equals(((Entry) o).output);
            }
            @Override public int hashCode() { return Objects.hash(time, output); }
            @Override public String toString() { return "(" + time + ", " + output + ")"; }
        }

        public final List<Entry> entries;
        private History(List<Entry> entries) { this.entries = entries; }

        History append(double t, List<OutputEvent> outs) {
            List<Entry> l = new ArrayList<>(entries);
            for (OutputEvent e : outs) l.add(new Entry(t, e));
            return new History(Collections.unmodifiableList(l));
        }

        @Override public boolean equals(Object o) { return o instanceof History && entries.equals(((History) o).entries); }
        @Override public int hashCode() { return entries.hashCode(); }
        @Override public String toString() { return entries.toString(); }
    }
}
