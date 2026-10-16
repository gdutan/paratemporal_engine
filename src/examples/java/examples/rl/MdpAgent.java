package examples.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

import paradevs.Law;
import paradevs.Para;
import paradevs.ms4.StateKeys;

/**
 * An agent acting in a {@link TabularMdp}: the actions come from {@link Para#decide} and the
 * environment noise from {@link Para#choose}. Unlike {@link MdpWalker} (a fixed policy folded into
 * the draws), the policy is not part of the model, so the same model serves the graph planner
 * (control), the forward engine (policy evaluation) and plain MS4 simulation (acting).
 *
 * One step has two DEVS events:
 * <ul>
 * <li>DECIDE at time t: a = decide("action", s, options); s' ~ P(.|s, a) ("transition"); the
 * reward r(s') is stored. A no-op (if enabled) keeps s with reward 0 and no draw.</li>
 * <li>REPORT at the same time t: outputs the reward on port "reward" and s' on port "state". If s'
 * is terminal, or the step limit is reached, the model goes passive. Otherwise the next decision
 * is after a duration tau: 1, or drawn from a duration law ("duration") for a semi-MDP.</li>
 * </ul>
 * The REPORT event is a DEVS event without a decision, so it is not a step. The reward is credited
 * at the decision time and the next state's value is discounted by gamma^tau, so with tau = 1 the
 * values are those of the usual MDP, Q(s,a) = sum_s' P(s'|s,a) (r(s') + gamma V(s')).
 */
public class MdpAgent extends AtomicModelImpl implements StateKeys.ParaStateKey {
    private static final long serialVersionUID = 1L;

    public enum Phase { DECIDE, REPORT, DONE }

    public final Port<Double> outReward = addOutputPort("reward", Double.class);
    public final Port<Integer> outState = addOutputPort("state", Integer.class);

    protected final String mdpName;
    protected final int startState, limit;
    protected final boolean noop;
    protected final double[] durations, durationProbs;   // null: one time unit per step

    protected int s, left;
    protected Phase phase;
    protected double reward, sigma;

    /**
     * @param limit     number of decisions before the model stops (-1: until a terminal state)
     * @param noop      whether {@link Para#NOOP} is offered as an extra option
     * @param durations step durations (null for 1), with probabilities {@code probs}
     */
    public MdpAgent(String mdpName, int startState, int limit, boolean noop, double[] durations, double[] probs) {
        super("MdpAgent");
        this.mdpName = mdpName; this.startState = startState; this.limit = limit; this.noop = noop;
        this.durations = durations; this.durationProbs = probs;
    }

    /** An unlimited agent with unit steps and no no-op, starting in {@code s}. */
    public MdpAgent(String mdpName, int startState) { this(mdpName, startState, -1, false, null, null); }

    @Override public void initialize() {
        super.initialize();
        s = startState; left = limit; reward = 0;
        boolean stop = TabularMdp.get(mdpName).isTerminal(s) || left == 0;
        phase = stop ? Phase.DONE : Phase.DECIDE;
        sigma = stop ? Double.POSITIVE_INFINITY : 0.0;
    }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {
        MessageBag out = new MessageBagImpl();
        if (phase == Phase.REPORT) { out.add(outReward, reward); out.add(outState, s); }
        return out;
    }

    /** The options at a decision: actions 0..nA-1, then the no-op if enabled. */
    public List<Object> options() { return optionList(TabularMdp.get(mdpName).nA, noop); }

    private static final List<List<Object>> OPTIONS = new ArrayList<>();
    static synchronized List<Object> optionList(int nA, boolean noop) {
        int k = 2 * nA + (noop ? 1 : 0);
        while (OPTIONS.size() <= k) OPTIONS.add(null);
        List<Object> l = OPTIONS.get(k);
        if (l == null) {
            l = new ArrayList<>();
            for (int a = 0; a < nA; a++) l.add(a);
            if (noop) l.add(Para.NOOP);
            l = Collections.unmodifiableList(l);
            OPTIONS.set(k, l);
        }
        return l;
    }

    @Override public void internalTransition() {
        TabularMdp mdp = TabularMdp.get(mdpName);
        switch (phase) {
            case DECIDE: {
                Object a = Para.decide("action", s, options());
                int s2;
                if (a == Para.NOOP) { s2 = s; reward = 0; }
                else { s2 = Para.choose("transition", mdp.transitionLaw(s, (Integer) a)); reward = mdp.rewardOnArrival(s2); }
                s = s2;
                if (left > 0) left--;
                phase = Phase.REPORT; sigma = 0.0;
                break;
            }
            case REPORT:
                reward = 0;
                if (mdp.isTerminal(s) || left == 0) { phase = Phase.DONE; sigma = Double.POSITIVE_INFINITY; break; }
                phase = Phase.DECIDE;
                sigma = durations == null ? 1.0 : Para.choose("duration", Law.of(asList(durations), durationProbs));
                break;
            default:
                sigma = Double.POSITIVE_INFINITY;
        }
    }

    private static List<Double> asList(double[] d) { List<Double> l = new ArrayList<>(); for (double x : d) l.add(x); return l; }

    public int state() { return s; }
    public Phase phase() { return phase; }

    /**
     * The complete state. sigma is left out: its only role is the time advance, which the key
     * already holds as the model's next event time. The tables and the duration law are fixed.
     */
    @Override public Object paraStateKey() { return Arrays.asList(mdpName, s, phase, reward, left, noop); }
}
