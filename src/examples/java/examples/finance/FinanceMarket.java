package examples.finance;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;

/**
 * Coupled model Trader <-> Portfolio, as the SES would declare it:
 *   FinanceMarket is made of Trader and Portfolio!
 *   Trader sends action to Portfolio!   Portfolio sends report to Trader!
 *   Portfolio sends report to FinanceMarket!
 */
public class FinanceMarket extends CoupledModelImpl {
    private static final long serialVersionUID = 1L;
    public final Port<Report> outReport;

    public FinanceMarket(double[][] q, double[] prices, int decisions) {
        super("FinanceMarket");
        Trader trader = new Trader(q, decisions);
        Portfolio portfolio = new Portfolio(prices);
        addChildModel(trader);
        addChildModel(portfolio);
        outReport = addOutputPort("report", Report.class);
        addCoupling(trader.outAction, portfolio.inAction);
        addCoupling(portfolio.outReport, trader.inReport);
        addCoupling(portfolio.outReport, outReport);
    }

    /** Rows of a CSV after its header line, split on commas. */
    public static List<String[]> csvRows(String path) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();   // header
            while ((line = br.readLine()) != null) if (!line.trim().isEmpty()) rows.add(line.split(","));
        }
        return rows;
    }

    /** Q-table: row s = {P(hold), P(sell), P(buy)} for s shares held (column 0 is the index). */
    public static double[][] loadQ(String path) throws IOException {
        List<String[]> rows = csvRows(path);
        double[][] q = new double[rows.size()][3];
        for (int s = 0; s < rows.size(); s++)
            for (int c = 0; c < 3; c++) q[s][c] = Double.parseDouble(rows.get(s)[c + 1]);
        return q;
    }

    /** prices[d] = close of data row d (d >= 1) in column {@code col} (1 = first stock). */
    public static double[] loadPrices(String path, int col) throws IOException {
        List<String[]> rows = csvRows(path);
        double[] p = new double[rows.size() + 1];
        for (int d = 1; d <= rows.size(); d++) p[d] = Double.parseDouble(rows.get(d - 1)[col]);
        return p;
    }
}
