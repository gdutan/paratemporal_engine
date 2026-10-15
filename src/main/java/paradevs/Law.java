package paradevs;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A finite discrete probability law P(dv | parents), the conditional law of one local draw
 * (main_paratemporal, Section 2.1). Atoms keep their insertion order, so enumeration is
 * deterministic. Zero-probability atoms are dropped.
 *
 * Probabilities are used as given and are not renormalized. A total far from 1 is rejected,
 * but small rounding (as in CSV-sourced tables) is accepted, so a model reproduces exactly
 * what its data says.
 */
public final class Law<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Largest accepted |total mass - 1|. */
    public static final double MASS_TOLERANCE = 1e-6;

    /** One positive atom (v, P({v})). */
    public static final class Atom<T> implements Serializable {
        private static final long serialVersionUID = 1L;
        public final T value;
        public final double prob;
        Atom(T value, double prob) { this.value = value; this.prob = prob; }
        @Override public String toString() { return value + ":" + prob; }
    }

    private final List<Atom<T>> atoms;

    private Law(List<Atom<T>> atoms) { this(atoms, MASS_TOLERANCE); }

    private Law(List<Atom<T>> atoms, double tolerance) {
        this.atoms = Collections.unmodifiableList(atoms);
        double total = 0;
        for (Atom<T> a : atoms) total += a.prob;
        if (atoms.isEmpty() || Math.abs(total - 1.0) > tolerance)
            throw new IllegalArgumentException("Law mass must be 1 (got " + total + "): " + atoms);
    }

    /** Law from alternating (value, probability) pairs: {@code Law.of("H", 0.5, "T", 0.5)}. */
    @SuppressWarnings("unchecked")
    public static <T> Law<T> of(Object... valueProbPairs) {
        if (valueProbPairs.length % 2 != 0)
            throw new IllegalArgumentException("Law.of expects (value, probability) pairs");
        List<Atom<T>> atoms = new ArrayList<>();
        for (int i = 0; i < valueProbPairs.length; i += 2)
            add(atoms, (T) valueProbPairs[i], ((Number) valueProbPairs[i + 1]).doubleValue());
        return new Law<>(atoms);
    }

    /** Law from an ordered map value -> probability. */
    public static <T> Law<T> of(Map<T, ? extends Number> probs) {
        List<Atom<T>> atoms = new ArrayList<>();
        for (Map.Entry<T, ? extends Number> e : probs.entrySet())
            add(atoms, e.getKey(), e.getValue().doubleValue());
        return new Law<>(atoms);
    }

    /** Law over {@code values[i]} with probability {@code probs[i]}. */
    public static <T> Law<T> of(List<T> values, double[] probs) {
        if (values.size() != probs.length)
            throw new IllegalArgumentException("values and probs differ in length");
        Map<T, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < probs.length; i++) m.merge(values.get(i), probs[i], Double::sum);
        return of(m);
    }

    /**
     * Like {@link #of(List, double[])}, but accepts a total mass within {@code massTolerance}
     * of 1. Use it only to reproduce source data that is known to be slightly improper. The
     * engine reports the resulting total mass, so the deviation stays visible.
     */
    public static <T> Law<T> asGiven(List<T> values, double[] probs, double massTolerance) {
        if (values.size() != probs.length)
            throw new IllegalArgumentException("values and probs differ in length");
        List<Atom<T>> atoms = new ArrayList<>();
        for (int i = 0; i < probs.length; i++) add(atoms, values.get(i), probs[i]);
        return new Law<>(atoms, massTolerance);
    }

    /** The Dirac law at {@code value}; choosing from it never branches. */
    public static <T> Law<T> dirac(T value) {
        List<Atom<T>> atoms = new ArrayList<>();
        atoms.add(new Atom<>(value, 1.0));
        return new Law<>(atoms);
    }

    private static <T> void add(List<Atom<T>> atoms, T value, double p) {
        if (p < 0 || Double.isNaN(p)) throw new IllegalArgumentException("Negative probability for " + value);
        if (p > 0) atoms.add(new Atom<>(value, p));
    }

    public List<Atom<T>> atoms() { return atoms; }

    /** True when the law has a single positive atom, so the draw needs no branching. */
    public boolean isDegenerate() { return atoms.size() == 1; }

    /** P({v}), or 0 if v is not an atom. */
    public double prob(Object v) {
        for (Atom<T> a : atoms) if (java.util.Objects.equals(a.value, v)) return a.prob;
        return 0;
    }

    /** One draw v ~ P using {@code rng}. */
    public T sample(Random rng) {
        double u = rng.nextDouble(), acc = 0;
        for (Atom<T> a : atoms) { acc += a.prob; if (u < acc) return a.value; }
        return atoms.get(atoms.size() - 1).value;
    }

    @Override public String toString() { return "Law" + atoms; }
}
