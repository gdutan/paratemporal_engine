package examples.finance;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

import paradevs.Law;
import paradevs.Para;

/**
 * Stochastic trading policy. On each portfolio report it draws the next action from the Q-table
 * row for the shares currently held, then sends the action one time unit later. This replaces the
 * Demo's CallGenerator: the action is chosen inside the model, and no state is injected.
 */
public class Trader extends AtomicModelImpl {
    private static final long serialVersionUID = 1L;
    public static final int HOLD = 0, BUY = 1, SELL = 2;   // Demo codes 10, 11, 12

    public final Port<Report> inReport = addInputPort("report", Report.class);
    public final Port<Integer> outAction = addOutputPort("action", Integer.class);

    /** q[shares] = {P(hold), P(sell), P(buy)}: the Q-table's column order. */
    protected final double[][] q;
    protected final int decisions;

    protected String phase = "wait";
    protected int left;
    protected int action = -1;
    protected double sigma = Double.POSITIVE_INFINITY;

    public Trader(double[][] q, int decisions) {
        super("Trader");
        this.q = q;
        this.decisions = decisions;
    }

    @Override public void initialize() {
        super.initialize();
        phase = "wait"; left = decisions; action = -1; sigma = Double.POSITIVE_INFINITY;
    }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {
        MessageBag out = new MessageBagImpl();
        if (phase.equals("act")) out.add(outAction, action);
        return out;
    }

    @Override public void internalTransition() {
        left--; phase = "wait"; action = -1; sigma = Double.POSITIVE_INFINITY;
    }

    @Override public void externalTransition(double e, MessageBag input) {
        Report r = inReport.getData(input).get(0);
        if (left <= 0) { phase = "done"; sigma = Double.POSITIVE_INFINITY; return; }
        double[] row = q[r.shares];
        action = Para.choose("action", Law.of(HOLD, row[0], SELL, row[1], BUY, row[2]));
        phase = "act"; sigma = 1.0;
    }
}
