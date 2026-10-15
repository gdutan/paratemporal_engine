package examples.baseball;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import examples.Check;
import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.Transducer;

/**
 * Acceptance test 3: baseball against a fresh run of the original BaseballDEVS_v7 engine
 * (runs/2026-10-09_baseball_baseline). The legacy tree merges on (level, bases, totalOuts, runs),
 * which with the batter index fixed by the level is the complete state. So every legacy node at
 * level L must equal the engine's exact law after L-1 plate appearances, pushed forward to
 * (bases, outs, totalOuts, runs).
 */
public class VerifyBaseball {
    static final String BASELINE = "runs/2026-10-09_baseball_baseline/RunHitToGameTree2026-10-09.csv";
    static final double LEGACY_EXP_RUNS_5 = 0.31552985850240994;

    static final Transducer<GameState> LAST_STATE = new Transducer<GameState>() {
        @Override public GameState initial() { return null; }
        @Override public GameState record(GameState xi, double t, List<OutputEvent> outs) {
            for (OutputEvent e : outs) if (e.port.equals("state")) xi = (GameState) e.value;
            return xi;
        }
    };

    /** Legacy nodes at {@code level}: key "bases|outs|totalOuts|runs" -> summed newProb. */
    static Map<String, Double> legacyLevel(int level) throws Exception {
        Pattern p = Pattern.compile("cState=(\\w+).*?outs=(\\d+), runs=(\\d+).*?newProb=([0-9.Ee-]+).*?totalOuts=(\\d+).*?level=(\\d+)");
        Map<String, Double> m = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(new File(BASELINE)))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher x = p.matcher(line);
                if (!x.find() || Integer.parseInt(x.group(6)) != level) continue;
                String key = x.group(1) + "|" + x.group(2) + "|" + x.group(5) + "|" + x.group(3);
                m.merge(key, Double.parseDouble(x.group(4)), Double::sum);
            }
        }
        return m;
    }

    static String key(GameState g) { return g.bases + "|" + g.outs + "|" + g.totalOuts + "|" + g.runs; }

    static ParaSpec<GameState> spec(int pa) {
        return ParaSpec.of(() -> new BaseballGame(BaseballGame.NORMAL, BaseballGame.SCORING, pa), LAST_STATE);
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("Baseball vs original BaseballDEVS_v7 engine");
        for (int pa : new int[]{5, 6}) {
            // The legacy tree also enumerates zero-probability outcomes (e.g. a triple for a
            // batter with P(triple) = 0). The paper discards zero-weight choices, so compare the
            // positive-probability states and report how many zero-mass states were dropped.
            Map<String, Double> all = legacyLevel(pa + 1);
            Map<String, Double> legacy = new LinkedHashMap<>();
            all.forEach((k, v) -> { if (v > 0) legacy.put(k, v); });
            System.out.println(pa + " PA: legacy states " + all.size() + ", of which zero-probability " + (all.size() - legacy.size()));
            ParaResult<GameState> ex = ParaEngine.run(spec(pa));
            ParaResult<GameState> mg = ParaEngine.run(spec(pa).merge(ParaSpec.Merge.SAFE));
            System.out.println(pa + " plate appearances, no merge  : " + ex.stats());
            System.out.println(pa + " plate appearances, safe merge: " + mg.stats());
            for (ParaResult<GameState> r : List.of(ex, mg)) {
                String tag = (r == ex ? "" : "merged ") + pa + "PA ";
                Map<String, Double> law = r.marginal(VerifyBaseball::key);
                double worst = 0;
                for (Map.Entry<String, Double> e : legacy.entrySet())
                    worst = Math.max(worst, Math.abs(law.getOrDefault(e.getKey(), 0.0) - e.getValue()));
                c.equal(tag + "same set of states as legacy (" + legacy.size() + ")", law.keySet().equals(legacy.keySet()), true);
                c.close(tag + "max |P_engine - P_legacy| over states", worst, 0, 1e-12);
                double legacyMass = legacy.values().stream().mapToDouble(Double::doubleValue).sum();
                c.close(tag + "total mass (data sums to 1.0001)", r.totalMass(), legacyMass, 1e-12);
            }
            c.equal(pa + "PA safe-merged outcomes = legacy merged states", (long) mg.distribution().size(), (long) legacy.size());
            if (pa == 5) {
                double er = ex.expectation(g -> g.runs);
                System.out.printf("  expected runs after 5 PA: engine %.15f  legacy %.15f%n", er, LEGACY_EXP_RUNS_5);
                c.close("expected runs after 5 PA", er, LEGACY_EXP_RUNS_5, 1e-12);
            }
        }
        c.finish();
    }
}
