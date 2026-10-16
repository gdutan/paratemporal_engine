package examples.rl;

import java.util.List;
import java.util.Objects;

import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;
import paradevs.engine.Transducer;

/**
 * n-step expected targets computed by the paratemporal engine:
 * <pre>  target(s) = E[ sum_{i=1..n} gamma^{i-1} R_i + gamma^n V(S_n) | S_0 = s ]</pre>
 * with V(S_n) = 0 when the walk ends in a terminal state first. The selection rule and the merge
 * mode decide whether the expectation is exact (exhaustive) or estimated (sampled).
 */
public final class NStepTargets {
    private NStepTargets() {}

    /** xi: discounted return so far, current state, steps taken, and gamma^steps. */
    public static final class Ret {
        public final double g, disc;
        public final int state, steps;
        Ret(double g, int state, int steps, double disc) { this.g = g; this.state = state; this.steps = steps; this.disc = disc; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Ret)) return false;
            Ret r = (Ret) o;
            return Double.compare(g, r.g) == 0 && state == r.state && steps == r.steps;
        }
        @Override public int hashCode() { return Objects.hash(g, state, steps); }
        @Override public String toString() { return "G=" + g + " s=" + state + " n=" + steps; }
    }

    /**
     * Folds the reported states into the discounted return. The model reports each state exactly
     * once (the start state first), so every report after the first is one step.
     *
     * The return g is declared as the additive reward ({@link Transducer.Additive}): each step adds
     * disc * r(s'), which depends on the rest (state, steps, disc) but not on g, so the transducer
     * also supports {@code Merge.EXPECTATION}.
     */
    static Transducer.Additive<Ret> returns(TabularMdp mdp) {
        return new Transducer.Additive<Ret>() {
            @Override public Ret initial() { return new Ret(0, -1, -1, 1); }
            @Override public Ret record(Ret xi, double t, List<OutputEvent> outs) {
                for (OutputEvent e : outs) {
                    if (!e.port.equals("state")) continue;
                    int s2 = (Integer) e.value;
                    if (xi.state < 0) { xi = new Ret(xi.g, s2, 0, 1); continue; }
                    xi = new Ret(xi.g + xi.disc * mdp.rewardOnArrival(s2), s2, xi.steps + 1, xi.disc * mdp.gamma);
                }
                return xi;
            }
            @Override public double reward(Ret xi) { return xi.g; }
            @Override public Ret withReward(Ret xi, double r) { return new Ret(r, xi.state, xi.steps, xi.disc); }
        };
    }

    /** One target computation. */
    public static final class Estimate {
        public final double value;
        public final long departures, choiceNodes, merged, maxLive, leaves;
        Estimate(double value, ParaResult.Stats st) {
            this.value = value; this.departures = st.departures; this.choiceNodes = st.choiceNodes;
            this.leaves = st.terminalLeaves;
            this.merged = st.merged; this.maxLive = st.maxLive;
        }
    }

    /**
     * Compute the n-step target from {@code start}, bootstrapping non-terminal leaves with {@code v}.
     *
     * @param rule   selection rule (exhaustive, monteCarlo(k), budget(B), ...)
     * @param merge  NONE, SAFE, or EXPECTATION (the target is linear in the return, so all three apply)
     * @param roots  independent roots (> 1 only for plain Monte Carlo rollouts)
     */
    public static Estimate target(TabularMdp mdp, int start, int n, double[] v, MdpWalker.Mode mode,
                                  SelectionRule rule, ParaSpec.Merge merge, int roots, long seed) {
        ParaSpec<Ret> spec = ParaSpec.of(() -> new MdpWalker(mdp.name, start, n, mode), returns(mdp))
            .selection(rule).merge(merge).roots(roots).seed(seed).maxLive(400_000);
        ParaResult<Ret> r = ParaEngine.run(spec);
        // E[g + disc V(s)]: affine in the return g with an offset that depends only on the rest.
        double value = r.expectation(x -> 1.0, x -> mdp.isTerminal(x.state) ? 0 : x.disc * v[x.state]);
        return new Estimate(value, r.stats());
    }
}
