package examples;

/** Minimal assertion helper for the acceptance tests (no test framework is on the classpath). */
public final class Check {
    private final String name;
    private int passed, failed;

    public Check(String name) { this.name = name; System.out.println("== " + name); }

    public void close(String what, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        report(ok, what, String.format("got %.12g want %.12g (tol %.1e)", got, want, tol));
    }

    public void equal(String what, Object got, Object want) {
        boolean ok = (got instanceof Number && want instanceof Number)
            ? ((Number) got).doubleValue() == ((Number) want).doubleValue()
            : got.equals(want);
        report(ok, what, "got " + got + " want " + want);
    }

    private void report(boolean ok, String what, String detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "  PASS " : "  FAIL ") + what + (ok ? "" : "  -> " + detail));
    }

    /** Print the tally and exit non-zero on any failure. */
    public void finish() {
        System.out.println("== " + name + ": " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
