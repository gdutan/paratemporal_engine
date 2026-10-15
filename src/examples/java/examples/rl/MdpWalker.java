package examples.rl;

import java.util.Arrays;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

import paradevs.Para;
import paradevs.ms4.StateKeys;

/**
 * An agent following a fixed policy in a {@link TabularMdp} for at most n steps, one step per time
 * unit. Used to compute n-step targets E[G_{t:t+n}] from a start state under the engine.
 *
 * Event k (time k) reports the current state on port "state", then the transition draws the next
 * state and moves. The draw therefore happens when a state is left, after the engine has merged the
 * branches that reached it, so a merged node samples its future once ("merge, then sample"). After
 * the n-th step, or on reaching a terminal state, one more event reports the final state and the
 * model goes passive.
 *
 * Draws: in {@link Mode#JOINT} one draw "step" from P^pi(.|s), so k branches per draw means k per
 * step; in {@link Mode#SEPARATE} a draw "action" from pi(.|s), then "transition" from P(.|s,a).
 */
public class MdpWalker extends AtomicModelImpl implements StateKeys.ParaStateKey {
    private static final long serialVersionUID = 1L;

    public enum Mode { JOINT, SEPARATE }

    public final Port<Integer> outState = addOutputPort("state", Integer.class);

    protected final String mdpName;
    protected final int startState, steps;
    protected final Mode mode;

    protected int s, left;
    protected boolean done;
    protected double sigma;

    public MdpWalker(String mdpName, int startState, int steps, Mode mode) {
        super("MdpWalker");
        this.mdpName = mdpName; this.startState = startState; this.steps = steps; this.mode = mode;
    }

    @Override public void initialize() {
        super.initialize();
        s = startState; left = steps; done = false; sigma = 1.0;
    }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {
        MessageBag out = new MessageBagImpl();
        out.add(outState, s);
        return out;
    }

    @Override public void internalTransition() {
        TabularMdp mdp = TabularMdp.get(mdpName);
        if (done || left == 0 || mdp.isTerminal(s)) { done = true; sigma = Double.POSITIVE_INFINITY; return; }
        if (mode == Mode.JOINT) {
            s = Para.choose("step", mdp.stepLaw(s));
        } else {
            int a = Para.choose("action", mdp.actionLaw(s));
            s = Para.choose("transition", mdp.transitionLaw(s, a));
        }
        left--;
        sigma = 1.0;
    }

    /** The complete state; the MDP tables are fixed and shared, so they are not part of it. */
    @Override public Object paraStateKey() { return Arrays.asList(mdpName, mode, s, left, done, sigma); }
}
