package examples.finance;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import examples.Check;
import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.Transducer;

/**
 * Acceptance test 2: single-stock finance against the verified Demo Deliverable baseline
 * (runs/2026-10-09_demo_baseline: AAPL validation data, Q_table_AAPL_stoch_1_15, depth 2).
 *
 * The Demo's IH merge keeps, for each share count s, P(s) and E[cash; s]. Both are linear in the
 * terminal law, so pushing the engine's exact terminal law forward to s must reproduce every
 * baseline row: prob, cont_value = E[cash + s * p_final; s], and profit.
 */
public class VerifyFinance {
    // Unmodified copies of Demo Deliverable/ParaDEVS/src/Models/data/input/{Q_Table,Train_Valid}/Single_Stock/,
    // bundled so that a clone of the repository runs this test on its own.
    static final String Q = "data/finance/Q_table_AAPL_stoch_1_15.csv";
    static final String PRICES = "data/finance/Valid_AAPL.csv";
    static final String BASELINE_DIR = "runs/2026-10-09_demo_baseline";

    /** xi = the latest portfolio report: a sufficient summary for terminal wealth. */
    static final Transducer<Report> LAST_REPORT = new Transducer<Report>() {
        @Override public Report initial() { return null; }
        @Override public Report record(Report xi, double t, List<OutputEvent> outs) {
            for (OutputEvent e : outs) if (e.port.equals("report")) xi = (Report) e.value;
            return xi;
        }
    };

    public static void main(String[] args) throws Exception {
        double[][] q = FinanceMarket.loadQ(Q);
        double[] prices = FinanceMarket.loadPrices(PRICES, 1);
        int depth = 2;
        double pFinal = prices[depth + 1];
        Check c = new Check("Finance single stock vs Demo Deliverable baseline (depth " + depth + ")");

        ParaSpec<Report> spec = ParaSpec.of(() -> new FinanceMarket(q, prices, depth), LAST_REPORT);
        ParaResult<Report> ex = ParaEngine.run(spec);
        ParaResult<Report> mg = ParaEngine.run(ParaSpec.of(() -> new FinanceMarket(q, prices, depth), LAST_REPORT)
            .merge(ParaSpec.Merge.SAFE));
        System.out.println("exhaustive, no merge : " + ex.stats());
        System.out.println("exhaustive, safe merge: " + mg.stats());
        for (Map.Entry<ParaResult.Outcome<Report>, Double> e : ex.distribution().entrySet())
            System.out.printf("  w=%.10f  %s%n", e.getValue(), e.getKey());

        // Baseline rows: profit, prob, cont_value, [shares]
        File dir = new File(BASELINE_DIR);
        File[] csv = dir.listFiles((d, n) -> n.startsWith("IH-PMF") && n.endsWith(".csv"));
        if (csv == null || csv.length != 1) throw new IllegalStateException("Expected one baseline CSV in " + dir.getAbsolutePath());
        List<String[]> rows = FinanceMarket.csvRows(csv[0].getPath());

        for (ParaResult<Report> r : List.of(ex, mg)) {
            String tag = r == ex ? "" : "merged ";
            Map<Integer, Double> prob = new TreeMap<>(r.marginal(x -> x.shares));
            Map<Integer, Double> cont = new TreeMap<>();
            for (Map.Entry<ParaResult.Outcome<Report>, Double> e : r.distribution().entrySet()) {
                Report x = e.getKey().xi;
                cont.merge(x.shares, e.getValue() * (x.cash + x.shares * pFinal), Double::sum);
            }
            double baseNet = 0;
            for (String[] row : rows) {
                int s = Integer.parseInt(row[3].replaceAll("[\\[\\] ]", ""));
                double bProfit = Double.parseDouble(row[0]), bProb = Double.parseDouble(row[1]), bCont = Double.parseDouble(row[2]);
                baseNet += bCont;
                double p = prob.getOrDefault(s, 0.0), v = cont.getOrDefault(s, 0.0);
                if (r == ex) System.out.printf("  shares=%d  prob %.12f vs %.12f   cont %.6f vs %.6f%n", s, p, bProb, v, bCont);
                c.close(tag + "P(shares=" + s + ")", p, bProb, 1e-12);
                c.close(tag + "cont_value(shares=" + s + ")", v, bCont, 1e-6);
                c.close(tag + "profit(shares=" + s + ")", v / p - Portfolio.INITIAL_CASH, bProfit, 1e-6);
            }
            baseNet -= Portfolio.INITIAL_CASH;
            double net = r.expectation(x -> x.cash + x.shares * pFinal) - Portfolio.INITIAL_CASH;
            if (r == ex) System.out.printf("  expected net value %.10f vs baseline %.10f%n", net, baseNet);
            c.close(tag + "expected net value", net, baseNet, 1e-6);
            c.equal(tag + "share counts match baseline", prob.size(), rows.size());
        }
        c.equal("exhaustive leaves = 3^depth", ex.stats().terminalLeaves, (long) Math.pow(3, depth));
        c.equal("all leaves end passive", ex.distribution().keySet().stream().allMatch(o -> o.status.name().equals("PASSIVE")), true);

        // Scaling: how far safe merging goes on deeper horizons (no baseline; laws must agree).
        System.out.println("scaling (no baseline):");
        for (int d = 3; d <= 6; d++) {
            final int dd = d;
            ParaResult<Report> a = ParaEngine.run(ParaSpec.of(() -> new FinanceMarket(q, prices, dd), LAST_REPORT));
            ParaResult<Report> b = ParaEngine.run(ParaSpec.of(() -> new FinanceMarket(q, prices, dd), LAST_REPORT).merge(ParaSpec.Merge.SAFE));
            double pf = prices[d + 1];
            double ea = a.expectation(x -> x.cash + x.shares * pf), eb = b.expectation(x -> x.cash + x.shares * pf);
            System.out.printf("  depth %d  no merge: %s%n           safe    : %s%n", d, a.stats(), b.stats());
            c.close("depth " + d + " merged expectation = unmerged", eb, ea, 1e-6);
        }
        c.finish();
    }
}
