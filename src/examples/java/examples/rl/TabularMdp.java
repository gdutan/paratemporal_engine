package examples.rl;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import paradevs.Law;

/**
 * A finite MDP with rewards on arrival, a fixed stochastic policy, and exact dynamic-programming
 * helpers. Instances are registered by name so that the DEVS model ({@link MdpWalker}) stores only
 * the name: the tables are then neither copied into every snapshot nor part of the merge key.
 *
 * Mirrors rl/paratemporal_py/mdp.py (same maps, same dynamics, same epsilon-soft policy), so the
 * Java and Python results can be compared directly.
 */
public final class TabularMdp {

    public final String name;
    public final int nS, nA;
    /** p[s][a][s']: transition probabilities. */
    final double[][][] p;
    /** reward[s']: reward received on arriving in s'. */
    final double[] reward;
    final boolean[] terminal;
    public final double gamma;
    public final int start;
    /** pi[s][a]: the evaluated policy. */
    double[][] pi;

    private static final Map<String, TabularMdp> REGISTRY = new ConcurrentHashMap<>();

    private TabularMdp(String name, double[][][] p, double[] reward, boolean[] terminal, double gamma, int start) {
        this.name = name; this.p = p; this.reward = reward; this.terminal = terminal; this.gamma = gamma; this.start = start;
        this.nS = p.length; this.nA = p[0].length;
        this.pi = new double[nS][nA];
        for (double[] row : pi) Arrays.fill(row, 1.0 / nA);
        REGISTRY.put(name, this);
    }

    public static TabularMdp get(String name) {
        TabularMdp m = REGISTRY.get(name);
        if (m == null) throw new IllegalStateException("No MDP registered as '" + name + "'");
        return m;
    }

    public boolean isTerminal(int s) { return terminal[s]; }
    public double rewardOnArrival(int s) { return reward[s]; }

    // ------------------------------------------------------------------ laws used by the model

    /** pi(.|s). */
    public Law<Integer> actionLaw(int s) {
        Map<Integer, Double> m = new LinkedHashMap<>();
        for (int a = 0; a < nA; a++) m.put(a, pi[s][a]);
        return Law.of(m);
    }

    /** P(.|s,a). */
    public Law<Integer> transitionLaw(int s, int a) {
        Map<Integer, Double> m = new LinkedHashMap<>();
        for (int s2 = 0; s2 < nS; s2++) if (p[s][a][s2] > 0) m.put(s2, p[s][a][s2]);
        return Law.of(m);
    }

    /** P^pi(.|s) = sum_a pi(a|s) P(.|s,a): one draw per step. */
    public Law<Integer> stepLaw(int s) {
        Map<Integer, Double> m = new LinkedHashMap<>();
        for (int s2 = 0; s2 < nS; s2++) {
            double q = 0;
            for (int a = 0; a < nA; a++) q += pi[s][a] * p[s][a][s2];
            if (q > 0) m.put(s2, q);
        }
        return Law.of(m);
    }

    // ------------------------------------------------------------------ exact dynamic programming

    /** V^pi by iterative policy evaluation (terminal states have value 0). */
    public double[] evaluatePolicy() {
        double[] v = new double[nS];
        for (int it = 0; it < 100_000; it++) {
            double delta = 0;
            double[] v2 = new double[nS];
            for (int s = 0; s < nS; s++) {
                if (terminal[s]) continue;
                double x = 0;
                for (int a = 0; a < nA; a++) x += pi[s][a] * q(v, s, a);
                v2[s] = x;
                delta = Math.max(delta, Math.abs(x - v[s]));
            }
            v = v2;
            if (delta < 1e-13) break;
        }
        return v;
    }

    /** V* by value iteration. */
    public double[] valueIteration() {
        double[] v = new double[nS];
        for (int it = 0; it < 100_000; it++) {
            double delta = 0;
            double[] v2 = new double[nS];
            for (int s = 0; s < nS; s++) {
                if (terminal[s]) continue;
                double best = Double.NEGATIVE_INFINITY;
                for (int a = 0; a < nA; a++) best = Math.max(best, q(v, s, a));
                v2[s] = best;
                delta = Math.max(delta, Math.abs(best - v[s]));
            }
            v = v2;
            if (delta < 1e-13) break;
        }
        return v;
    }

    private double q(double[] v, int s, int a) {
        double x = 0;
        for (int s2 = 0; s2 < nS; s2++) {
            double pr = p[s][a][s2];
            if (pr > 0) x += pr * (reward[s2] + gamma * (terminal[s2] ? 0 : v[s2]));
        }
        return x;
    }

    /** Set pi to the epsilon-soft greedy policy for V* (ties to the lowest action, as numpy argmax). */
    public TabularMdp epsilonSoftOptimal(double eps) {
        double[] v = valueIteration();
        for (int s = 0; s < nS; s++) {
            int best = 0;
            for (int a = 1; a < nA; a++) if (q(v, s, a) > q(v, s, best) + 1e-12) best = a;
            for (int a = 0; a < nA; a++) pi[s][a] = eps / nA + (a == best ? 1 - eps : 0);
        }
        return this;
    }

    // ------------------------------------------------------------------ problems

    /** Sutton and Barto's 19-state random walk: states 0..20, ends 0 (reward -1) and 20 (+1), start 10. */
    public static TabularMdp randomWalk19() {
        int nS = 21;
        double[][][] p = new double[nS][1][nS];
        double[] r = new double[nS];
        boolean[] term = new boolean[nS];
        term[0] = term[20] = true;
        r[0] = -1; r[20] = 1;
        for (int s = 1; s < 20; s++) { p[s][0][s - 1] = 0.5; p[s][0][s + 1] = 0.5; }
        return new TabularMdp("randomWalk19", p, r, term, 1.0, 10);
    }

    static final String[] FROZEN_LAKE_8X8 = {
        "SFFFFFFF", "FFFFFFFF", "FFFHFFFF", "FFFFFHFF",
        "FFFHFFFF", "FHHFFFHF", "FHFFHFHF", "FFFHFFFG" };

    /** Gymnasium's slippery FrozenLake 8x8: actions Left, Down, Right, Up; each slips to the sides w.p. 1/3. */
    public static TabularMdp frozenLake8x8(double gamma) {
        String[] map = FROZEN_LAKE_8X8;
        int n = map.length, nS = n * n, nA = 4;
        int[][] move = { {0, -1}, {1, 0}, {0, 1}, {-1, 0} };   // (drow, dcol) for L, D, R, U
        double[][][] p = new double[nS][nA][nS];
        double[] r = new double[nS];
        boolean[] term = new boolean[nS];
        for (int s = 0; s < nS; s++) {
            char c = map[s / n].charAt(s % n);
            term[s] = c == 'H' || c == 'G';
            if (c == 'G') r[s] = 1;
        }
        for (int s = 0; s < nS; s++) {
            if (term[s]) continue;
            int row = s / n, col = s % n;
            for (int a = 0; a < nA; a++)
                for (int d : new int[] { (a + 3) % 4, a, (a + 1) % 4 }) {
                    int r2 = Math.min(n - 1, Math.max(0, row + move[d][0]));
                    int c2 = Math.min(n - 1, Math.max(0, col + move[d][1]));
                    p[s][a][r2 * n + c2] += 1.0 / 3;
                }
        }
        return new TabularMdp("frozenLake8x8", p, r, term, gamma, 0);
    }

    // ------------------------------------------------------------------ access for bridges (examples.rl.env)

    /**
     * A copy registered under {@code newName}: same (shared, read-only) tables, its own policy. Lets
     * several sessions evaluate different policies on one problem without clashing in the registry.
     */
    public TabularMdp copy(String newName) {
        TabularMdp m = new TabularMdp(newName, p, reward, terminal, gamma, start);
        for (int s = 0; s < nS; s++) m.pi[s] = pi[s].clone();
        return m;
    }

    /** Remove a registered MDP (e.g. a session copy that is no longer used). */
    public static void unregister(String name) { REGISTRY.remove(name); }

    /** P(s'|s,a). */
    public double transitionProb(int s, int a, int s2) { return p[s][a][s2]; }

    /** pi(a|s). */
    public double policyProb(int s, int a) { return pi[s][a]; }

    /** Replace the evaluated policy; each row must be a probability vector over the nA actions. */
    public TabularMdp setPolicy(double[][] newPi) {
        if (newPi.length != nS) throw new IllegalArgumentException("policy needs " + nS + " rows");
        double[][] q = new double[nS][];
        for (int s = 0; s < nS; s++) {
            if (newPi[s].length != nA) throw new IllegalArgumentException("policy rows need " + nA + " entries");
            double sum = 0;
            for (double x : newPi[s]) { if (x < 0) throw new IllegalArgumentException("negative probability"); sum += x; }
            if (Math.abs(sum - 1) > Law.MASS_TOLERANCE) throw new IllegalArgumentException("row " + s + " sums to " + sum);
            q[s] = newPi[s].clone();
        }
        pi = q;
        return this;
    }
}
