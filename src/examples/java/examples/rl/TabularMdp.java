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

    private double q(double[] v, int s, int a) { return q(v, s, a, gamma); }

    /**
     * Q(s,a) = sum_s' P(s'|s,a) (r(s') + beta V(s')), V = 0 at terminal states. beta is the
     * discount of one step: gamma for unit steps, E[gamma^tau] for a random duration tau drawn
     * independently of s' (semi-MDP).
     */
    public double q(double[] v, int s, int a, double beta) {
        double x = 0;
        for (int s2 = 0; s2 < nS; s2++) {
            double pr = p[s][a][s2];
            if (pr > 0) x += pr * (reward[s2] + beta * (terminal[s2] ? 0 : v[s2]));
        }
        return x;
    }

    /** Finite-horizon value iteration: n Bellman-optimality steps from v0 (V_n = T^n v0), discount beta per step. */
    public double[] valueIterationSteps(int n, double[] v0, double beta) {
        double[] v = v0.clone();
        for (int k = 0; k < n; k++) {
            double[] v2 = new double[nS];
            for (int s = 0; s < nS; s++) {
                if (terminal[s]) continue;
                double best = Double.NEGATIVE_INFINITY;
                for (int a = 0; a < nA; a++) best = Math.max(best, q(v, s, a, beta));
                v2[s] = best;
            }
            v = v2;
        }
        return v;
    }

    /** The evaluated policy pi[s][a] (live array). */
    public double[][] policy() { return pi; }

    /** Replace the evaluated policy. */
    public TabularMdp setPolicy(double[][] newPi) {
        for (int s = 0; s < nS; s++) pi[s] = newPi[s].clone();
        return this;
    }

    /**
     * The same MDP with one extra action nA, "stay": the state is unchanged and the reward is 0
     * (matching {@link MdpAgent}'s no-op; terminal-only rewards are assumed).
     */
    public static TabularMdp withNoop(TabularMdp m) {
        int nA2 = m.nA + 1;
        double[][][] p2 = new double[m.nS][nA2][];
        for (int s = 0; s < m.nS; s++) {
            for (int a = 0; a < m.nA; a++) p2[s][a] = m.p[s][a].clone();
            p2[s][m.nA] = new double[m.nS];
            if (!m.terminal[s]) {
                if (m.reward[s] != 0) throw new IllegalArgumentException("withNoop assumes zero reward in non-terminal states");
                p2[s][m.nA][s] = 1;
            }
        }
        return new TabularMdp(m.name + "+noop", p2, m.reward.clone(), m.terminal.clone(), m.gamma, m.start);
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

    /**
     * A copy of this MDP (same dynamics and terminals, uniform policy) registered as {@code newName},
     * with reward(s') + bonus[s'] on arrival in s'. Used for per-step rewards that make returns
     * differ between paths, so that safe merging rarely fires.
     */
    public TabularMdp withArrivalBonus(String newName, double[] bonus) {
        double[] r = reward.clone();
        for (int s = 0; s < nS; s++) r[s] += bonus[s];
        return new TabularMdp(newName, p, r, terminal, gamma, start);
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
}
