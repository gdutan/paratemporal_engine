package paradevs.engine;

import java.util.Objects;
import java.util.function.Supplier;

import com.ms4systems.devs.core.model.AtomicModel;

/**
 * Paratemporal specification PTS = (M, rep, EF, AP, Sel, MM, Phi) (main_paratemporal, eq. "pts").
 *
 * <ul>
 * <li>M: {@link #model} builds a fresh, uninitialized MS4 model (atomic or coupled).</li>
 * <li>rep: v0 uses the eager representation (psλ): outputs are whatever the model stores.</li>
 * <li>EF: the input trace, the transducer, the acceptor, and the horizon m in persistent entries.</li>
 * <li>AP: chronological (every branch with the earliest next event advances); fixed.</li>
 * <li>Sel: {@link #selection}. MM: {@link #merge}. Phi is applied to the result by the caller.</li>
 * </ul>
 */
public final class ParaSpec<X> {

    /** Whether equal-key children are merged. */
    public enum Merge { NONE, SAFE }

    /** Acceptor: an optional stopping rule over common information (xi, time, entry count). */
    public interface Acceptor<X> { boolean accept(X xi, double time, int entries); }

    final Supplier<? extends AtomicModel> model;
    final Transducer<X> transducer;
    InputTrace trace = InputTrace.NONE;
    int horizon = Integer.MAX_VALUE;
    Acceptor<X> acceptor = null;
    SelectionRule selection = SelectionRule.exhaustive();
    Merge merge = Merge.NONE;
    int roots = 1;
    long seed = 1L;
    long maxLive = 5_000_000L;

    private ParaSpec(Supplier<? extends AtomicModel> model, Transducer<X> transducer) {
        this.model = Objects.requireNonNull(model);
        this.transducer = Objects.requireNonNull(transducer);
    }

    public static <X> ParaSpec<X> of(Supplier<? extends AtomicModel> model, Transducer<X> transducer) {
        return new ParaSpec<>(model, transducer);
    }

    public ParaSpec<X> trace(InputTrace t) { trace = Objects.requireNonNull(t); return this; }
    /** Stop at the m-th persistent entry (the initial state is entry 1). */
    public ParaSpec<X> horizon(int m) { if (m < 1) throw new IllegalArgumentException("m >= 1"); horizon = m; return this; }
    public ParaSpec<X> acceptor(Acceptor<X> a) { acceptor = a; return this; }
    public ParaSpec<X> selection(SelectionRule s) { selection = Objects.requireNonNull(s); return this; }
    public ParaSpec<X> merge(Merge m) { merge = Objects.requireNonNull(m); return this; }
    /** Number of initial roots, each of weight 1/N (N > 1 is for Monte Carlo). */
    public ParaSpec<X> roots(int n) { if (n < 1) throw new IllegalArgumentException("N >= 1"); roots = n; return this; }
    public ParaSpec<X> seed(long s) { seed = s; return this; }
    /** Safety cap on simultaneously live branches. */
    public ParaSpec<X> maxLive(long cap) { maxLive = cap; return this; }
}
