package examples.finance;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

/**
 * Deterministic portfolio. It executes each action at that day's close price, with the same rules
 * as the Demo's Portfolio in BuyingStock: buy adds a share, sell removes one only if a share is
 * held, hold changes nothing. It then reports its state in zero time.
 */
public class Portfolio extends AtomicModelImpl {
    private static final long serialVersionUID = 1L;
    public static final double INITIAL_CASH = 1_000_000.0;

    public final Port<Integer> inAction = addInputPort("action", Integer.class);
    public final Port<Report> outReport = addOutputPort("report", Report.class);

    /** prices[d] = close on decision day d (d >= 1). */
    protected final double[] prices;

    protected String phase = "report";
    protected double cash;
    protected int shares;
    protected int day;
    protected double sigma = 0;

    public Portfolio(double[] prices) {
        super("Portfolio");
        this.prices = prices;
    }

    @Override public void initialize() {
        super.initialize();
        phase = "report"; cash = INITIAL_CASH; shares = 0; day = 0; sigma = 0;
    }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {
        MessageBag out = new MessageBagImpl();
        if (phase.equals("report")) out.add(outReport, new Report(cash, shares, day));
        return out;
    }

    @Override public void internalTransition() { phase = "passive"; sigma = Double.POSITIVE_INFINITY; }

    @Override public void externalTransition(double e, MessageBag input) {
        int a = inAction.getData(input).get(0);
        day++;
        double p = prices[day];
        if (a == Trader.BUY) { shares++; cash -= p; }
        else if (a == Trader.SELL && shares > 0) { shares--; cash += p; }
        phase = "report"; sigma = 0;
    }
}
