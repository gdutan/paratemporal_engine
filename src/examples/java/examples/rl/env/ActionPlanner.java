package examples.rl.env;

import examples.rl.TabularMdp;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;

/**
 * Extension point for online action selection: at a state, give every action a value from a
 * depth-n lookahead computed by the engine. {@link MdpSession#plan} and the Python
 * {@code ParaEnv.plan(...)} call whichever planner is installed.
 *
 * <p>Contract. {@link #actionValues} returns an array of length {@code nA + 1}: entries
 * {@code 0..nA-1} are the action values Q_n(s, a), and the last entry is the work spent (engine
 * departures, the unit used by {@link examples.rl.NStepTargets}). Actions that a planner does
 * not evaluate get {@code Double.NaN}. The planner must not change the session's live state or
 * its step RNG; it gets its own seed.
 *
 * <p>The default, {@link PolicyLookaheadPlanner}, forces the first action and then follows the
 * session's policy (policy evaluation, one engine run per action). A graph-mode planner built on
 * {@code Para.decide} (maximising over actions at every decision) can implement this interface
 * unchanged and be installed with {@link MdpSession#setPlanner}.
 */
public interface ActionPlanner {

    double[] actionValues(TabularMdp mdp, int state, int n, double[] v,
                          SelectionRule rule, ParaSpec.Merge merge, int roots, long seed);

    /** A short name for logs. */
    default String name() { return getClass().getSimpleName(); }
}
