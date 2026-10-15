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
