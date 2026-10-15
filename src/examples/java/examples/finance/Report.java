package examples.finance;

import java.io.Serializable;
import java.util.Objects;

/** Portfolio report message: cash, shares held, and decision day. Immutable, value equality. */
public final class Report implements Serializable {
    private static final long serialVersionUID = 1L;
    public final double cash;
    public final int shares;
    public final int day;

    public Report(double cash, int shares, int day) { this.cash = cash; this.shares = shares; this.day = day; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Report)) return false;
        Report r = (Report) o;
        return Double.compare(cash, r.cash) == 0 && shares == r.shares && day == r.day;
    }
    @Override public int hashCode() { return Objects.hash(cash, shares, day); }
    @Override public String toString() { return "Report(cash=" + cash + ", shares=" + shares + ", day=" + day + ")"; }
}
