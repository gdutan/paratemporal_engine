package paradevs.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fixed input trace A = ((t_j, a_j)), j = 1..p, with strictly increasing times. Simultaneous
 * messages are bundled into one entry (main_paratemporal, Section 2.3). Each branch keeps its
 * own cursor j into the trace.
 */
public final class InputTrace {
    public static final InputTrace NONE = new InputTrace(Collections.emptyList());

    /** All messages arriving at one time, as (input port name, value) pairs. */
    public static final class Bundle {
        public final double time;
        public final List<String> ports;
        public final List<Serializable> values;
        Bundle(double time, List<String> ports, List<Serializable> values) {
            this.time = time; this.ports = ports; this.values = values;
        }
    }

    private final List<Bundle> bundles;
    private InputTrace(List<Bundle> bundles) { this.bundles = Collections.unmodifiableList(bundles); }

    public int size() { return bundles.size(); }
    public Bundle get(int j) { return bundles.get(j); }
    /** Time of entry j, or +infinity once the trace is exhausted. */
    public double timeAt(int j) { return j < bundles.size() ? bundles.get(j).time : Double.POSITIVE_INFINITY; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<Bundle> bundles = new ArrayList<>();

        /** Add a message; a message at the same time as the previous one joins its bundle. */
        public Builder at(double time, String port, Serializable value) {
            Bundle last = bundles.isEmpty() ? null : bundles.get(bundles.size() - 1);
            if (last != null && Double.compare(last.time, time) == 0) {
                last.ports.add(port); last.values.add(value);
            } else {
                if (last != null && time < last.time) throw new IllegalArgumentException("Input times must be nondecreasing");
                List<String> p = new ArrayList<>(); p.add(port);
                List<Serializable> v = new ArrayList<>(); v.add(value);
                bundles.add(new Bundle(time, p, v));
            }
            return this;
        }

        public InputTrace build() { return bundles.isEmpty() ? NONE : new InputTrace(new ArrayList<>(bundles)); }
    }
}
