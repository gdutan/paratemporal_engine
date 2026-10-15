package examples.tutorial;

import java.util.Arrays;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

import paradevs.Law;
import paradevs.Para;

/**
 * Tutorial atomic model: a single server with no queue.
 * idle --customer--> busy for a random service time (0.5 w.p. 0.6, 1.5 w.p. 0.4) --> emits done, idle.
 * A customer arriving while busy is lost; the remaining service time is kept.
 */
public class Server extends AtomicModelImpl {
    private static final long serialVersionUID = 1L;

    /** The only paratemporal-specific line: the service-time law. */
    static final Law<Double> SERVICE = Law.of(Arrays.asList(0.5, 1.5), new double[]{0.6, 0.4});

    public final Port<Integer> inCustomer = addInputPort("customer", Integer.class);
    public final Port<Integer> outDone = addOutputPort("done", Integer.class);

    protected String phase;
    protected int current;
    protected double sigma;

    public Server() { super("Server"); }

    @Override public void initialize() {
        super.initialize();
        phase = "idle"; current = -1; sigma = Double.POSITIVE_INFINITY;
    }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {
        MessageBag out = new MessageBagImpl();
        if (phase.equals("busy")) out.add(outDone, current);
        return out;
    }

    @Override public void internalTransition() {
        phase = "idle"; current = -1; sigma = Double.POSITIVE_INFINITY;
    }

    @Override public void externalTransition(double e, MessageBag input) {
        int id = inCustomer.getData(input).get(0);
        if (phase.equals("idle")) {
            current = id;
            sigma = Para.choose("service", SERVICE);   // the engine branches here
            phase = "busy";
        } else {
            sigma -= e;                                 // lost customer; keep serving
        }
    }
}
