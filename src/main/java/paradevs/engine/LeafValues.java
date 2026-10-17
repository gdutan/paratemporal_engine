package paradevs.engine;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * The leaf-value hook V(obs) of a plan. It is called once per backward pass with every distinct
 * leaf observation, so a learner in Python (through JPype) is called once per plan, not once per
 * leaf. Terminal (passive) branches are not leaves: their value is 0.
 */
@FunctionalInterface
public interface LeafValues {

    /** One value per observation, in the given order. */
    double[] values(List<Object> observations);

    /** V = 0. */
    static LeafValues zero() { return obs -> new double[obs.size()]; }

    /** From a single-leaf function (evaluated leaf by leaf). */
    static LeafValues of(ToDoubleFunction<Object> v) {
        return obs -> {
            double[] out = new double[obs.size()];
            for (int i = 0; i < out.length; i++) out[i] = v.applyAsDouble(obs.get(i));
            return out;
        };
    }

    /** A table indexed by integer observations. */
    static LeafValues table(double[] v) { return of(o -> v[(Integer) o]); }
}
