package examples.baseball;

import java.util.HashMap;
import java.util.Map;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;

/**
 * Deterministic base/out/run state machine, transcribed from BaseballDEVS_v7 BallGame.dnl,
 * quirks included (e.g. a double with a man on first goes to SecondAndThird with no run). An out
 * leaves runners in place; the third out clears the bases and resets outs. Reports in zero time.
 */
public class BallGame extends AtomicModelImpl {
    private static final long serialVersionUID = 1L;

    public static final String[] HITS = {"YrOut", "Single", "Double", "Triple", "Homer", "Walk"};
    static final String BE = "BasesEmpty", F = "ManOnFirst", S = "ManOnSecond", T = "ManOnThird",
        FS = "FirstAndSecond", FT = "FirstAndThird", ST = "SecondAndThird", BL = "BasesLoaded";

    /** bases -> {next bases per hit}, and runs scored per hit, in HITS order. */
    static final Map<String, String[]> NEXT = new HashMap<>();
    static final Map<String, int[]> RUNS = new HashMap<>();
    static {
        rule(BE, new String[]{BE, F,  S,  T, BE, F},  new int[]{0, 0, 0, 0, 1, 0});
        rule(F,  new String[]{F,  FS, ST, T, BE, FS}, new int[]{0, 0, 0, 1, 2, 0});
        rule(S,  new String[]{S,  FT, S,  T, BE, FS}, new int[]{0, 0, 1, 1, 2, 0});
        rule(T,  new String[]{T,  F,  S,  T, BE, FT}, new int[]{0, 1, 1, 1, 2, 0});
        rule(FS, new String[]{FS, BL, ST, T, BE, BL}, new int[]{0, 0, 1, 2, 3, 0});
        rule(FT, new String[]{FT, FS, ST, T, BE, BL}, new int[]{0, 1, 1, 2, 3, 0});
        rule(ST, new String[]{ST, FT, S,  T, BE, BL}, new int[]{0, 1, 2, 2, 3, 0});
        rule(BL, new String[]{BL, BL, ST, T, BE, BL}, new int[]{0, 1, 2, 3, 4, 1});
    }
    private static void rule(String b, String[] next, int[] runs) { NEXT.put(b, next); RUNS.put(b, runs); }

    /** States in which the batter uses his scoring-position probabilities. */
    public static boolean scoringPosition(String bases) { return !(bases.equals(BE) || bases.equals(F)); }

    public final Port<String> inHit = addInputPort("hit", String.class);
    public final Port<GameState> outState = addOutputPort("state", GameState.class);

    protected String phase = "report";
    protected String bases;
    protected int outs, totalOuts, runs;
    protected double sigma = 0;

    public BallGame() { super("BallGame"); }

    @Override public void initialize() {
        super.initialize();
        phase = "report"; bases = BE; outs = 0; totalOuts = 0; runs = 0; sigma = 0;
    }

    @Override public Double getTimeAdvance() { return sigma; }

    @Override public MessageBag getOutput() {
        MessageBag out = new MessageBagImpl();
        if (phase.equals("report")) out.add(outState, new GameState(bases, outs, totalOuts, runs));
        return out;
    }

    @Override public void internalTransition() { phase = "passive"; sigma = Double.POSITIVE_INFINITY; }

    @Override public void externalTransition(double e, MessageBag input) {
        String hit = inHit.getData(input).get(0);
        int h = java.util.Arrays.asList(HITS).indexOf(hit);
        runs += RUNS.get(bases)[h];
        bases = NEXT.get(bases)[h];
        if (h == 0) {
            outs++; totalOuts++;
            if (outs == 3) { outs = 0; bases = BE; }
        }
        phase = "report"; sigma = 0;
    }
}
