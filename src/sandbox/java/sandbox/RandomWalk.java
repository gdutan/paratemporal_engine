package sandbox;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

import paradevs.Law;
import paradevs.Para;

/**
 * Sandbox template: a random walk on the integers, one step per time unit for n steps.
 *
 * The next step is drawn when a state is entered (in initialize() and in the internal
 * transition) and stored, so getOutput() only reports stored state, as the eager (psλ)
 * representation requires. Copy this file to start a new experiment.
 */
public class RandomWalk extends AtomicModelImpl {
    private static final long serialVersionUID = 1L;

    public final Port<Integer> outPosition = addOutputPort("position", Integer.class);

    protected final int steps;
    protected final double pUp;

    protected int position, left, nextStep;
    protected double sigma;

    public RandomWalk(int steps, double pUp) {
        super("RandomWalk");
        this.steps = steps;
        this.pUp = pUp;
    }

    private int drawStep() {
        return Para.choose("step", Law.of(+1, pUp, -1, 1 - pUp));
    }

    @Override public void initialize() {
        super.initialize();
        position = 0; left = steps; nextStep = drawStep(); sigma = 1.0;
    }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {           // report the position after this step
        MessageBag out = new MessageBagImpl();
        out.add(outPosition, position + nextStep);
        return out;
    }

    @Override public void internalTransition() {
        position += nextStep;
        left--;
        if (left > 0) { nextStep = drawStep(); sigma = 1.0; }
        else          { nextStep = 0; sigma = Double.POSITIVE_INFINITY; }
    }
}
