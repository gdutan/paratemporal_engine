package sandbox.fastcopy;

import java.lang.reflect.Field;
import java.util.List;

import com.ms4systems.devs.core.model.AtomicModel;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;
import com.ms4systems.devs.core.simulation.impl.SimulatorImpl;

/**
 * MEASUREMENT PROTOTYPE. Copies the MS4 bookkeeping of a model onto a freshly built copy: the
 * simulator's event times and flags, and the model's initialized flag, recursively over the children
 * of a coupled model (in declared order). This is what lets a hand-written paraCopy() copy only the
 * model's own fields. Fields are looked up once and cached.
 */
final class Bookkeeping {
    private Bookkeeping() {}

    private static final Field[] SIM = fields(SimulatorImpl.class, "lastEventTime", "nextEventTime", "currentTime",
        "modelTimeAdvance", "initialized", "cachedOutputTime");
    private static final Field CACHED_OUTPUT = fields(SimulatorImpl.class, "cachedOutput")[0];
    private static final Field MODEL_INIT = fields(AtomicModelImpl.class, "initialized")[0];

    private static Field[] fields(Class<?> c, String... names) {
        Field[] f = new Field[names.length];
        try {
            for (int i = 0; i < names.length; i++) { f[i] = c.getDeclaredField(names[i]); f[i].setAccessible(true); }
        } catch (NoSuchFieldException e) { throw new IllegalStateException(e); }
        return f;
    }

    static void copy(AtomicModel from, AtomicModel to) {
        try {
            if (from.getSimulator() instanceof SimulatorImpl && to.getSimulator() instanceof SimulatorImpl) {
                Object a = from.getSimulator(), b = to.getSimulator();
                for (Field f : SIM) f.set(b, f.get(a));
                CACHED_OUTPUT.set(b, null);   // the engine clears it after every event anyway
            }
            if (from instanceof AtomicModelImpl && to instanceof AtomicModelImpl) MODEL_INIT.set(to, MODEL_INIT.get(from));
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        if (from instanceof CoupledModelImpl) {
            List<AtomicModel> ca = ((CoupledModelImpl) from).getChildren(), cb = ((CoupledModelImpl) to).getChildren();
            if (ca.size() != cb.size()) throw new IllegalStateException("paraCopy() built a different number of children");
            for (int i = 0; i < ca.size(); i++) copy(ca.get(i), cb.get(i));
        }
    }
}
