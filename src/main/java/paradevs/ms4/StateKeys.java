package paradevs.ms4;

import java.beans.PropertyChangeSupport;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.model.AtomicModel;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;
import com.ms4systems.devs.core.simulation.Simulator;

/**
 * Canonical value of a model's complete state, used in the merge key
 * (main_paratemporal, Prop. "Safe merging"). Equal keys mean equal continuations.
 *
 * For each atomic model, in declared child order, the key holds the class, the name, the MS4
 * simulator's last and next event times, and every instance field declared by the model's own
 * classes, including bookkeeping fields such as phase, sigma, and currentTime. Only structural
 * references are excluded: ports, simulators, parent/child model references, property-change
 * support, and MS4 helper options. These never differ between copies of one model. A model can
 * supply its own key by implementing {@link ParaStateKey}; it is then responsible for the key
 * being complete.
 *
 * Values are compared with equals() when the class overrides it. Otherwise they are compared by
 * their serialized bytes, and as a last resort by identity. Missing a true equality only prevents
 * a merge, so every fallback stays safe.
 */
public final class StateKeys {
    private StateKeys() {}

    /** Optional: a model that knows its own complete, canonical state. */
    public interface ParaStateKey {
        Object paraStateKey();
    }

    public static Object of(AtomicModel model) {
        List<Object> key = new ArrayList<>();
        collect(model, key, null, true);
        return key;
    }

    /**
     * The key with event times taken relative to {@code now}, for time-homogeneous models (used by
     * the graph planner). Each atomic model contributes its time to the next event, tN - now. A
     * component of a coupled model also contributes its elapsed time, now - tL, since an input can
     * reach it and its external transition sees the elapsed time. A top-level atomic model receives
     * no inputs under the planner, so its elapsed time cannot affect its future and is left out.
     * Model fields are included exactly as in {@link #of}, so a field holding an absolute time still
     * prevents merges across times (safe).
     */
    public static Object relative(AtomicModel model, double now) {
        List<Object> key = new ArrayList<>();
        collect(model, key, now, true);
        return key;
    }

    private static void collect(AtomicModel m, List<Object> key, Double now, boolean top) {
        if (m instanceof CoupledModelImpl) {
            key.add("coupled:" + m.getName());
            for (AtomicModel c : ((CoupledModelImpl) m).getChildren()) collect(c, key, now, false);
            return;
        }
        key.add(m.getClass().getName());
        key.add(m.getName());
        Simulator s = m.getSimulator();
        if (now == null) {
            key.add(s.getLastEventTime());
            key.add(s.getNextEventTime());
        } else {
            key.add(s.getNextEventTime() - now);
            if (!top) key.add(now - s.getLastEventTime());
        }
        if (m instanceof ParaStateKey) { key.add(canonical(((ParaStateKey) m).paraStateKey())); return; }
        for (Class<?> c = m.getClass(); c != null && c != AtomicModelImpl.class && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || structural(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    key.add(f.getName());
                    key.add(canonical(f.get(m)));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    key.add(new Object());   // unreadable field: unique, so this state never merges
                }
            }
        }
    }

    private static boolean structural(Class<?> t) {
        return Port.class.isAssignableFrom(t) || Simulator.class.isAssignableFrom(t)
            || AtomicModel.class.isAssignableFrom(t) || PropertyChangeSupport.class.isAssignableFrom(t)
            || t.getName().startsWith("com.ms4systems.devs.helpers.");
    }

    static Object canonical(Object v) {
        if (v == null || v instanceof Number || v instanceof String || v instanceof Boolean
            || v instanceof Character || v instanceof Enum) return v;
        if (v.getClass().isArray()) {
            int n = Array.getLength(v);
            List<Object> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(canonical(Array.get(v, i)));
            return l;
        }
        if (v instanceof List) { List<Object> l = new ArrayList<>(); for (Object o : (List<?>) v) l.add(canonical(o)); return l; }
        if (v instanceof Set) { Set<Object> s = new HashSet<>(); for (Object o : (Set<?>) v) s.add(canonical(o)); return s; }
        if (v instanceof Collection) { List<Object> l = new ArrayList<>(); for (Object o : (Collection<?>) v) l.add(canonical(o)); return l; }
        if (v instanceof Map) {
            Map<Object, Object> m = new HashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) m.put(canonical(e.getKey()), canonical(e.getValue()));
            return m;
        }
        if (overridesEquals(v.getClass())) return v;
        if (v instanceof Serializable) return new Snapshots.Bytes(Snapshots.save((Serializable) v));
        return new Object();
    }

    private static boolean overridesEquals(Class<?> c) {
        try { return c.getMethod("equals", Object.class).getDeclaringClass() != Object.class; }
        catch (NoSuchMethodException e) { return false; }
    }
}
