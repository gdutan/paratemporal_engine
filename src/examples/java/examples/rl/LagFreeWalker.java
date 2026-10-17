package examples.rl;

import java.util.List;
import java.util.Objects;

import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;

import paradevs.engine.OutputEvent;
import paradevs.engine.Transducer;

/**
 * {@link MdpWalker} variant whose observation does not hold the current state (used by
 * {@link CostGrowth}).
 *
 * Why: safe merging keys a branch by its model state and its observation xi. A DEVS model emits its
 * output before its internal transition, so the state reached by a step is reported only at the
 * next event; right after a draw, where the engine merges, NStepTargets' xi still holds the
 * previous state. Merging then only joins branches that agree on (previous state, current state).
 *
 * Here the observation keeps only what the target needs: the discounted return so far, the step
 * count, and, at the final report, the bootstrap term gamma^n V(S_n). The model marks its final
 * report on a second port "final" (it knows the walk ends when it reports a terminal state or has
 * no steps left), so the transducer does not need to remember the state. Branches then merge on the
 * current state alone. The n-step targets are identical; only the work changes.
 */
public class LagFreeWalker extends MdpWalker {
    private static final long serialVersionUID = 1L;

    public final Port<Integer> outFinal = addOutputPort("final", Integer.class);

    public LagFreeWalker(String mdpName, int startState, int steps, Mode mode) {
        super(mdpName, startState, steps, mode);
    }

    @Override public MessageBag getOutput() {
        MessageBag out = super.getOutput();
        TabularMdp mdp = TabularMdp.get(mdpName);
        if (!done && (left == 0 || mdp.isTerminal(s))) out.add(outFinal, s);
        return out;
    }

    /** xi: return so far (with the bootstrap term added at the final report), steps, gamma^steps. */
    public static final class Obs {
        public final double g, disc;
        public final int steps;
        public final boolean fin;
        Obs(double g, int steps, double disc, boolean fin) { this.g = g; this.steps = steps; this.disc = disc; this.fin = fin; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Obs)) return false;
            Obs r = (Obs) o;
            return Double.compare(g, r.g) == 0 && steps == r.steps && fin == r.fin;
        }
        @Override public int hashCode() { return Objects.hash(g, steps, fin); }
        @Override public String toString() { return "G=" + g + " n=" + steps + (fin ? " final" : ""); }
    }

    /** Folds "state" reports into the return and adds gamma^n V(S_n) on the "final" report. */
    static Transducer<Obs> returns(TabularMdp mdp, double[] v) {
        return new Transducer<Obs>() {
            @Override public Obs initial() { return new Obs(0, -1, 1, false); }
            @Override public Obs record(Obs xi, double t, List<OutputEvent> outs) {
                for (OutputEvent e : outs) {
                    if (!e.port.equals("state")) continue;
                    int s2 = (Integer) e.value;
                    xi = xi.steps < 0 ? new Obs(0, 0, 1, false)
                        : new Obs(xi.g + xi.disc * mdp.rewardOnArrival(s2), xi.steps + 1, xi.disc * mdp.gamma, false);
                }
                for (OutputEvent e : outs) {
                    if (!e.port.equals("final")) continue;
                    int s2 = (Integer) e.value;
                    xi = new Obs(xi.g + (mdp.isTerminal(s2) ? 0 : xi.disc * v[s2]), xi.steps, xi.disc, true);
                }
                return xi;
            }
        };
    }
}
