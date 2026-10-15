package examples.tutorial;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

/** Tutorial atomic model: emits customers 1..n at times 1, 2, ..., n, then stops. Deterministic. */
public class Arrivals extends AtomicModelImpl {
    private static final long serialVersionUID = 1L;

    public final Port<Integer> outCustomer = addOutputPort("customer", Integer.class);

    protected final int n;
    protected int next;
    protected double sigma;

    public Arrivals(int n) { super("Arrivals"); this.n = n; }

    @Override public void initialize() { super.initialize(); next = 1; sigma = 1.0; }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {
        MessageBag out = new MessageBagImpl();
        out.add(outCustomer, next);
        return out;
    }

    @Override public void internalTransition() {
        next++;
        sigma = next > n ? Double.POSITIVE_INFINITY : 1.0;
    }
}
