package examples.rl.env;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import examples.rl.MdpWalker;
import examples.rl.TabularMdp;
import paradevs.Para;
import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;
import paradevs.engine.Transducer;

/**
 * Default {@link ActionPlanner}: for each action a, one engine run of
 * <pre>  Q_n(s, a) = E[ sum_{i=1..n} gamma^{i-1} R_i + gamma^n V(S_n) | S_0 = s, A_0 = a, then pi ]</pre>
 * (V = 0 at terminal states). The first transition is drawn from P(.|s,a); later steps follow the
 * session's policy exactly as {@link examples.rl.NStepTargets} does, so
 * {@code sum_a pi(a|s) Q_n(s,a)} equals the n-step target of s.
 */
public final class PolicyLookaheadPlanner implements ActionPlanner {

    public final MdpWalker.Mode mode;

    public PolicyLookaheadPlanner() { this(MdpWalker.Mode.JOINT); }
    public PolicyLookaheadPlanner(MdpWalker.Mode mode) { this.mode = Objects.requireNonNull(mode); }

    @Override public double[] actionValues(TabularMdp mdp, int state, int n, double[] v,
                                           SelectionRule rule, ParaSpec.Merge merge, int roots, long seed) {
        if (n < 1) throw new IllegalArgumentException("n >= 1");
        double[] out = new double[mdp.nA + 1];
        if (mdp.isTerminal(state)) { out[mdp.nA] = 0; return out; }   // every action is worth 0 at a terminal state
        double work = 0;
        for (int a = 0; a < mdp.nA; a++) {
            final int a0 = a;
            ParaSpec<Ret> spec = ParaSpec.of(() -> new ForcedFirstAction(mdp.name, state, n, mode, a0), returns(mdp))
                .selection(rule).merge(merge).roots(roots).seed(seed + 7919L * a).maxLive(400_000);
            ParaResult<Ret> r = ParaEngine.run(spec);
            out[a] = r.expectation(x -> x.g + (mdp.isTerminal(x.state) ? 0 : x.disc * v[x.state]));
            work += r.stats().departures;
        }
        out[mdp.nA] = work;
        return out;
    }

    /** An {@link MdpWalker} whose first step uses a fixed action. */
    static final class ForcedFirstAction extends MdpWalker {
        private static final long serialVersionUID = 1L;
        final int a0;

        ForcedFirstAction(String mdpName, int start, int steps, Mode mode, int a0) {
            super(mdpName, start, steps, mode);
            this.a0 = a0;
        }

        @Override public void internalTransition() {
            TabularMdp mdp = TabularMdp.get(mdpName);
            if (!done && left == steps && left > 0 && !mdp.isTerminal(s)) {
                s = Para.choose("transition", mdp.transitionLaw(s, a0));
                left--;
                sigma = 1.0;
                return;
            }
            super.internalTransition();
        }

        @Override public Object paraStateKey() { return Arrays.asList(super.paraStateKey(), a0); }
    }

    /** Discounted return so far, current state, gamma^steps (as NStepTargets.Ret). */
    static final class Ret {
        final double g, disc;
        final int state, steps;
        Ret(double g, int state, int steps, double disc) { this.g = g; this.state = state; this.steps = steps; this.disc = disc; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Ret)) return false;
            Ret r = (Ret) o;
            return Double.compare(g, r.g) == 0 && state == r.state && steps == r.steps;
        }
        @Override public int hashCode() { return Objects.hash(g, state, steps); }
    }

    static Transducer<Ret> returns(TabularMdp mdp) {
        return new Transducer<Ret>() {
            @Override public Ret initial() { return new Ret(0, -1, -1, 1); }
            @Override public Ret record(Ret xi, double t, List<OutputEvent> outs) {
                for (OutputEvent e : outs) {
                    if (!e.port.equals("state")) continue;
                    int s2 = (Integer) e.value;
                    if (xi.state < 0) { xi = new Ret(0, s2, 0, 1); continue; }
                    xi = new Ret(xi.g + xi.disc * mdp.rewardOnArrival(s2), s2, xi.steps + 1, xi.disc * mdp.gamma);
                }
                return xi;
            }
        };
    }
}
