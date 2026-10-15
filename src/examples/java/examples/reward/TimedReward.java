package examples.reward;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

import paradevs.Law;
import paradevs.Para;

/**
 * The timed reward model of main_paratemporal, Section "A worked execution", in the eager (psλ)
 * representation, written as an ordinary MS4 atomic model.
 *
 * waiting w(q): passive. An action draws the next phase q' ~ P(.|q) and, eagerly, the reward
 * r ~ K_R(.|q, q'), then holds in pending p(q', a, r) for D = 1. At expiry it emits r and returns
 * to waiting w(q'). An action during pending preempts it: no reward, and a new pending state is
 * built from q'. An action at expiry is confluent: emit r, then build the new pending state.
 */
public class TimedReward extends AtomicModelImpl {
    private static final long serialVersionUID = 1L;
    static final double D = 1.0;

    public final Port<String> inAction = addInputPort("action", String.class);
    public final Port<Integer> outReward = addOutputPort("reward", Integer.class);

    protected String phase = "waiting";
    protected int q;          // waiting: current phase; pending: the phase q' just entered
    protected String act;     // pending: the action that created it
    protected int r;          // pending: the eagerly drawn reward
    protected double sigma = Double.POSITIVE_INFINITY;

    public TimedReward() { super("TimedReward"); }

    /** P(q' = 1 | q) = 3/4 if q = 0, 2/5 if q = 1. */
    static Law<Integer> nextPhase(int q) {
        double p1 = q == 0 ? 0.75 : 0.4;
        return Law.of(0, 1 - p1, 1, p1);
    }

    /** P(r = 1 | q, q') = 1/5 + 3/5 q' + 1/10 q. */
    static Law<Integer> rewardLaw(int q, int qn) {
        double p1 = 0.2 + 0.6 * qn + 0.1 * q;
        return Law.of(0, 1 - p1, 1, p1);
    }

    @Override public void initialize() {
        super.initialize();
        phase = "waiting"; q = 0; act = null; r = -1; sigma = Double.POSITIVE_INFINITY;
    }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {
        MessageBag out = new MessageBagImpl();
        if (phase.equals("pending")) out.add(outReward, r);
        return out;
    }

    @Override public void internalTransition() {   // pending expired: back to waiting w(q')
        phase = "waiting"; act = null; r = -1; sigma = Double.POSITIVE_INFINITY;
    }

    @Override public void externalTransition(double e, MessageBag input) {
        enterPending(q, inAction.getData(input).get(0));
    }
    // confluentTransition: MS4 default = internal, then external(0), as the model requires.

    private void enterPending(int qPrev, String a) {
        int qn = Para.choose("phase", nextPhase(qPrev));
        int rr = Para.choose("reward", rewardLaw(qPrev, qn));
        phase = "pending"; q = qn; act = a; r = rr; sigma = D;
    }
}
