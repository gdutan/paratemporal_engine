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
 *
 * <p>Under {@link ParaSpec.Merge#EXPECTATION} the collector is no longer the law of (xi, h): each
 * merged branch carries the weight-averaged reward of the branches it joined. What stays exact is
 * the law of the rest of xi (the observation with its reward zeroed) together with the stop
 * status, and the expectation of anything affine in the reward with coefficients that depend only
 * on the rest. Such a result therefore answers only
 * {@link #expectation(ToDoubleFunction, ToDoubleFunction)}, {@link #expectedReward()},
 * {@link #totalMass()}, {@link #residual()} and {@link #stats()}; {@link #distribution()},
 * {@link #marginal(Function)} and the general {@link #expectation(ToDoubleFunction)} throw
 * IllegalStateException.
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
        public long merged;           // children absorbed by a merge (safe or expectation-only)
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
    /** The reward split, when the transducer declares one; otherwise null. */
    private final Transducer.Additive<X> additive;
    private final boolean expectationOnly;

    @SuppressWarnings("unchecked")
    ParaResult(Transducer<X> transducer, ParaSpec.Merge merge) {
        this.additive = transducer instanceof Transducer.Additive ? (Transducer.Additive<X>) transducer : null;
        this.expectationOnly = merge == ParaSpec.Merge.EXPECTATION;
    }

    void collect(X xi, StopStatus h, double w) {
        collector.merge(new Outcome<>(xi, h), w, Double::sum);
        stats.terminalLeaves++;
    }

    /** True when the run used {@link ParaSpec.Merge#EXPECTATION}: only expectations are available. */
    public boolean expectationOnly() { return expectationOnly; }

    private void refuseUnderExpectation(String query) {
        if (expectationOnly)
            throw new IllegalStateException(query + " is not available: this result was produced with Merge.EXPECTATION, "
                + "which joins branches with different rewards and keeps only their weight-averaged reward, so the "
                + "distribution of the reward (and of anything nonlinear in it) is lost. Use expectedReward() or "
                + "expectation(slope, offset), or rerun with Merge.SAFE or Merge.NONE for the distribution.");
    }

    /** The weighted terminal distribution, in first-collected order. Refused under EXPECTATION. */
    public Map<Outcome<X>, Double> distribution() {
        refuseUnderExpectation("distribution()");
        return Collections.unmodifiableMap(collector);
    }
    /** Omitted mass r from partial selection; with exhaustive rules it is 0. */
    public double residual() { return residual; }
    public Stats stats() { return stats; }

    /** Total collected mass; merging (of either kind) preserves it. */
    public double totalMass() { double s = 0; for (double w : collector.values()) s += w; return s; }

    /**
     * Phi for a linear statistic: sum_k w_k f(xi_k). Refused under EXPECTATION, because f may be
     * nonlinear in the reward; use {@link #expectation(ToDoubleFunction, ToDoubleFunction)} there.
     */
    public double expectation(ToDoubleFunction<X> f) {
        refuseUnderExpectation("expectation(f) for a general f");
        double s = 0;
        for (Map.Entry<Outcome<X>, Double> e : collector.entrySet()) s += e.getValue() * f.applyAsDouble(e.getKey().xi);
        return s;
    }

    /**
     * E[ slope(rest) * G + offset(rest) ], where G is the declared additive reward and rest is xi
     * with its reward zeroed ({@code withReward(xi, 0.0)}). This is the most general statistic that
     * Merge.EXPECTATION keeps exact: affine in G, with coefficients that may be any function of
     * the rest (the merge key holds the rest exactly, and a merged group shares its future, so
     * E[a(rest) G] splits by linearity). The rule is enforced by construction: {@code slope} and
     * {@code offset} only ever see the rest, never the reward. Examples: E[G] is
     * {@code expectation(x -> 1, x -> 0)}; an n-step target E[G + gamma^n V(S_n)] is
     * {@code expectation(x -> 1, x -> x.disc * v[x.state])}; E[f(rest)] for any f, including the
     * probability of an event of the rest, is {@code expectation(x -> 0, f)}.
     *
     * <p>Available under every merge mode, provided the transducer implements
     * {@link Transducer.Additive}; otherwise IllegalStateException.
     */
    public double expectation(ToDoubleFunction<X> slope, ToDoubleFunction<X> offset) {
        if (additive == null)
            throw new IllegalStateException("expectation(slope, offset) needs a transducer that implements "
                + "Transducer.Additive (it splits xi into reward and rest)");
        double s = 0;
        for (Map.Entry<Outcome<X>, Double> e : collector.entrySet()) {
            X xi = e.getKey().xi;
            X rest = additive.withReward(xi, 0.0);
            s += e.getValue() * (slope.applyAsDouble(rest) * additive.reward(xi) + offset.applyAsDouble(rest));
        }
        return s;
    }

    /** E[G], the expected accumulated additive reward (needs {@link Transducer.Additive}). */
    public double expectedReward() { return expectation(x -> 1.0, x -> 0.0); }

    /**
     * Push the distribution forward through {@code g}, summing the weights of equal images.
     * Refused under EXPECTATION.
     */
    public <K> Map<K, Double> marginal(Function<X, K> g) {
        refuseUnderExpectation("marginal(g)");
        Map<K, Double> m = new LinkedHashMap<>();
        for (Map.Entry<Outcome<X>, Double> e : collector.entrySet()) m.merge(g.apply(e.getKey().xi), e.getValue(), Double::sum);
        return m;
    }
}
