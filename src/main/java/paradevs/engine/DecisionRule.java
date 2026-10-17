package paradevs.engine;

import java.util.List;

import paradevs.Para;

/**
 * How a decision node combines its action values Q(d, a) in the backward pass: the max (control,
 * value iteration) or the policy average (evaluation). Root decisions always report every Q(d, a),
 * whatever the rule, so a caller can pick its own action (greedy, epsilon-greedy, ...).
 */
@FunctionalInterface
public interface DecisionRule {

    /**
     * @param q        Q(d, a) per option; NaN for options that were not expanded
     * @param expanded which options were expanded
     */
    double combine(String label, Object obs, List<?> options, double[] q, boolean[] expanded);

    /** max_a Q(d, a) over the expanded actions. */
    static DecisionRule max() {
        return (label, obs, options, q, expanded) -> {
            double best = Double.NEGATIVE_INFINITY;
            for (int a = 0; a < q.length; a++) if (expanded[a]) best = Math.max(best, q[a]);
            return best;
        };
    }

    /** sum_a pi(a|obs) Q(d, a). Every action with positive probability must have been expanded. */
    static DecisionRule policy(Para.Policy pi) {
        return (label, obs, options, q, expanded) -> {
            double[] p = pi.probabilities(label, obs, options);
            double v = 0;
            for (int a = 0; a < q.length; a++) {
                if (p[a] == 0) continue;
                if (!expanded[a]) throw new IllegalStateException("Policy gives probability " + p[a] + " to action "
                    + options.get(a) + " at '" + label + "' obs=" + obs + ", which the plan did not expand (prune policy?)");
                v += p[a] * q[a];
            }
            return v;
        };
    }
}
