package paradevs.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import paradevs.Law;

/**
 * SELECTOptions / TransOptions (main_paratemporal, Table "selection"). Given the conditional law
 * of the pending draw, returns the values to explore, each with its weight factor alpha, plus the
 * omitted probability fraction epsilon. The child weight is w * alpha (eq. "mass").
 *
 * For the soundness results, a rule may depend on the draw and on fresh randomness, but not on
 * the branch weight or on other branches. The one exception is {@link #budget(int)}, whose sample
 * count depends on the branch weight; it stays unbiased because the weight is fixed by the past
 * (see its comment).
 */
public interface SelectionRule {

    Options select(String label, Law<?> law, Random rng);

    /** The engine calls this form; {@code w} is the weight of the branch making the draw. */
    default Options select(String label, Law<?> law, Random rng, double w) { return select(label, law, rng); }

    /** Values with weight factors, plus the omitted fraction. */
    final class Options {
        public final List<Object> values;
        public final List<Double> factors;
        public final double omitted;
        Options(List<Object> values, List<Double> factors, double omitted) {
            this.values = values; this.factors = factors; this.omitted = omitted;
        }
        static Options single(Object v, double alpha) {
            return new Options(Collections.singletonList(v), Collections.singletonList(alpha), 0);
        }
    }

    /** Exhaustive: every positive atom (v, P({v})); epsilon = 0. */
    static SelectionRule exhaustive() {
        return (label, law, rng) -> {
            List<Object> v = new ArrayList<>(); List<Double> a = new ArrayList<>();
            for (Law.Atom<?> atom : law.atoms()) { v.add(atom.value); a.add(atom.prob); }
            return new Options(v, a, 0);
        };
    }

    /** Monte Carlo: one v ~ P with factor 1. Use with N roots of weight 1/N. */
    static SelectionRule monteCarlo() {
        return (label, law, rng) -> Options.single(law.sample(rng), 1.0);
    }

    /**
     * Monte Carlo with k branches per draw: k independent values v_1..v_k ~ P, each with factor 1/k.
     * Unbiased for any statistic, since E[sum_i (1/k) f(v_i)] = E f. Repeated values stay separate
     * children (safe merging, if on, joins them). Use with one root; k = 1 is {@link #monteCarlo()}.
     */
    static SelectionRule monteCarlo(int k) {
        if (k < 1) throw new IllegalArgumentException("k >= 1");
        return (label, law, rng) -> sample(law, rng, k);
    }

    /**
     * Budget allocation: a branch of weight w draws k = max(1, round(B * w)) independent values,
     * each with factor 1/k. With merging, this spends about B samples per draw level in proportion to
     * the merged weights (the reallocation fix of the RL plan, Section "Monte Carlo at every stage").
     * Unbiased: k is a function of w, which the past fixes before the draw, so conditionally on the
     * past the k values are i.i.d. from P and E[sum_i (1/k) f(v_i)] = E f. Keeping k >= 1 avoids the
     * need for Russian roulette.
     */
    static SelectionRule budget(int B) {
        if (B < 1) throw new IllegalArgumentException("B >= 1");
        return new SelectionRule() {
            @Override public Options select(String label, Law<?> law, Random rng) { return sample(law, rng, B); }
            @Override public Options select(String label, Law<?> law, Random rng, double w) {
                return sample(law, rng, (int) Math.max(1, Math.round(B * w)));
            }
        };
    }

    /** k i.i.d. draws with factor 1/k each. */
    private static Options sample(Law<?> law, Random rng, int k) {
        List<Object> v = new ArrayList<>(k); List<Double> a = new ArrayList<>(k);
        for (int i = 0; i < k; i++) { v.add(law.sample(rng)); a.add(1.0 / k); }
        return new Options(v, a, 0);
    }

    /**
     * Partial exhaustive: atoms with P({v}) >= minProb; epsilon = 1 - P(kept). The engine adds the
     * omitted mass to the residual r, which bounds the true probabilities (eq. "residual").
     */
    static SelectionRule partialExhaustive(double minProb) {
        return (label, law, rng) -> {
            List<Object> v = new ArrayList<>(); List<Double> a = new ArrayList<>();
            double kept = 0, total = 0;
            for (Law.Atom<?> atom : law.atoms()) {
                total += atom.prob;
                if (atom.prob >= minProb) { v.add(atom.value); a.add(atom.prob); kept += atom.prob; }
            }
            return new Options(v, a, total - kept);
        };
    }

    /** Hybrid: a rule per draw label (e.g. exhaustive on "phase", Monte Carlo on "noise"). */
    static SelectionRule hybrid(Map<String, SelectionRule> byLabel, SelectionRule otherwise) {
        return new SelectionRule() {
            @Override public Options select(String label, Law<?> law, Random rng) {
                return byLabel.getOrDefault(label, otherwise).select(label, law, rng);
            }
            @Override public Options select(String label, Law<?> law, Random rng, double w) {
                return byLabel.getOrDefault(label, otherwise).select(label, law, rng, w);
            }
        };
    }
}
