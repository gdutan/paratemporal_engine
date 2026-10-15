package examples.baseball;

import java.util.Arrays;
import java.util.List;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

import paradevs.Law;
import paradevs.Para;

/**
 * Replaces HitMaker. On each game report, the current batter draws the plate-appearance outcome:
 * from his scoring-position row when runners are in scoring position, otherwise from his normal
 * row. The outcome is sent one time unit later, and the lineup then advances.
 */
public class Batter extends AtomicModelImpl {
    private static final long serialVersionUID = 1L;
    static final List<String> HITS = Arrays.asList(BallGame.HITS);
    /** The source data has a row summing to 1.0004 (Machado, scoring position); kept as given. */
    static final double DATA_MASS_TOLERANCE = 1e-3;

    public final Port<GameState> inState = addInputPort("state", GameState.class);
    public final Port<String> outHit = addOutputPort("hit", String.class);

    protected final double[][] normal, scoring;   // [batter][hit]
    protected final int plateAppearances;

    protected String phase = "wait";
    protected int index, left;
    protected String hit;
    protected double sigma = Double.POSITIVE_INFINITY;

    public Batter(double[][] normal, double[][] scoring, int plateAppearances) {
        super("Batter");
        this.normal = normal; this.scoring = scoring; this.plateAppearances = plateAppearances;
    }

    @Override public void initialize() {
        super.initialize();
        phase = "wait"; index = 0; left = plateAppearances; hit = null; sigma = Double.POSITIVE_INFINITY;
    }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {
        MessageBag out = new MessageBagImpl();
        if (phase.equals("atBat")) out.add(outHit, hit);
        return out;
    }

    @Override public void internalTransition() {
        index = (index + 1) % normal.length; left--; phase = "wait"; hit = null; sigma = Double.POSITIVE_INFINITY;
    }

    @Override public void externalTransition(double e, MessageBag input) {
        GameState g = inState.getData(input).get(0);
        if (left <= 0) { phase = "done"; sigma = Double.POSITIVE_INFINITY; return; }
        double[] row = BallGame.scoringPosition(g.bases) ? scoring[index] : normal[index];
        hit = Para.choose("hit", Law.asGiven(HITS, row, DATA_MASS_TOLERANCE));
        phase = "atBat"; sigma = 1.0;
    }
}
