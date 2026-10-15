package paradevs.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Engine output: the collector P^ = sum_k w_k delta_(xi_k, h_k) over terminal observations and
 * stop statuses, the omitted mass r, and run statistics (main_paratemporal, eq. "collector").
 */
public final class ParaResult<X> {

    /** A terminal observation and its stop status. */
    public static final class Outcome<X> {
        public final X xi;
        public final StopStatus status;
        Outcome(X xi, StopStatus status) { this.xi = xi; this.status = status; }
        @Override public boolean equals(Object o) {
            return o instanceof Outcome && Objects.equals(xi, ((Outcome<?>) o).xi) && status == ((Outcome<?>) o).status;
        }
        @Override public int hashCode() { return Objects.hash(xi, status); }
        @Override public String toString() { return status + " " + xi; }
    }

    /** Counters for comparing exploration cost. */
    public static final class Stats {
        public long terminalLeaves;   // terminal branches collected (before summing equal outcomes)
        public long choiceNodes;      // expansions of a non-degenerate draw
        public long departures;       // departure runs, including replays
        public long merged;           // children absorbed by a safe merge
        public long maxLive;          // largest live set
        public long millis;
        @Override public String toString() {
            return "leaves=" + terminalLeaves + " choiceNodes=" + choiceNodes + " departures=" + departures
                + " merged=" + merged + " maxLive=" + maxLive + " ms=" + millis;
        }
    }

    final Map<Outcome<X>, Double> collector = new LinkedHashMap<>();
    double residual;
    final Stats stats = new Stats();

    void collect(X xi, StopStatus h, double w) {
        collector.merge(new Outcome<>(xi, h), w, Double::sum);
        stats.terminalLeaves++;
    }

    /** The weighted terminal distribution, in first-collected order. */
    public Map<Outcome<X>, Double> distribution() { return Collections.unmodifiableMap(collector); }
    /** Omitted mass r from partial selection; with exhaustive rules it is 0. */
    public double residual() { return residual; }
    public Stats stats() { return stats; }

    public double totalMass() { double s = 0; for (double w : collector.values()) s += w; return s; }

    /** Phi for a linear statistic: sum_k w_k f(xi_k). */
    public double expectation(ToDoubleFunction<X> f) {
        double s = 0;
        for (Map.Entry<Outcome<X>, Double> e : collector.entrySet()) s += e.getValue() * f.applyAsDouble(e.getKey().xi);
        return s;
    }

    /** Push the distribution forward through {@code g}, summing the weights of equal images. */
    public <K> Map<K, Double> marginal(Function<X, K> g) {
        Map<K, Double> m = new LinkedHashMap<>();
        for (Map.Entry<Outcome<X>, Double> e : collector.entrySet()) m.merge(g.apply(e.getKey().xi), e.getValue(), Double::sum);
        return m;
    }
}
