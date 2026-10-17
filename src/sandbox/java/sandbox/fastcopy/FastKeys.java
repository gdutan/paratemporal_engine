package sandbox.fastcopy;

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
import java.util.concurrent.ConcurrentHashMap;

import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.model.AtomicModel;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;
import com.ms4systems.devs.core.simulation.Simulator;

import paradevs.ms4.Snapshots;
import paradevs.ms4.StateKeys;

/**
 * MEASUREMENT PROTOTYPE (option c). The same key as paradevs.ms4.StateKeys (same equalities), built
 * more cheaply:
 * <ul>
 * <li>the per-class field list and the "does this class override equals" answer are computed once and
 *     cached, instead of calling getDeclaredFields(), setAccessible() and getMethod("equals") on every
 *     completed departure;</li>
 * <li>a primitive array becomes one wrapper around a copy of it (Arrays.equals / Arrays.hashCode)
 *     instead of a list of boxed values: for doubles both compare bit patterns, so the equality is the same;</li>
 * <li>the whole key caches its hash code, so the merge map does not walk it again on every lookup.</li>
 * </ul>
 */
final class FastKeys {
    private FastKeys() {}

    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> EQUALS = new ConcurrentHashMap<>();

    static Object of(AtomicModel model) {
        List<Object> key = new ArrayList<>();
        collect(model, key);
        return new Key(key);
    }

    /** A key list with a cached hash code. */
    static final class Key {
        final List<Object> parts; final int h;
        Key(List<Object> parts) { this.parts = parts; this.h = parts.hashCode(); }
        @Override public boolean equals(Object o) { return o instanceof Key && h == ((Key) o).h && parts.equals(((Key) o).parts); }
        @Override public int hashCode() { return h; }
    }

    /** Value wrapper for a copy of a primitive array. */
    static final class Prim {
        final Object a; final int h;
        Prim(Object a) {
            this.a = a;
            if (a instanceof double[]) h = java.util.Arrays.hashCode((double[]) a);
            else if (a instanceof int[]) h = java.util.Arrays.hashCode((int[]) a);
            else if (a instanceof long[]) h = java.util.Arrays.hashCode((long[]) a);
            else if (a instanceof boolean[]) h = java.util.Arrays.hashCode((boolean[]) a);
            else h = java.util.Arrays.deepHashCode(new Object[]{a});
        }
        @Override public boolean equals(Object o) {
            return o instanceof Prim && h == ((Prim) o).h && java.util.Objects.deepEquals(a, ((Prim) o).a);
        }
        @Override public int hashCode() { return h; }
    }

    private static void collect(AtomicModel m, List<Object> key) {
        if (m instanceof CoupledModelImpl) {
            key.add("coupled:" + m.getName());
            for (AtomicModel c : ((CoupledModelImpl) m).getChildren()) collect(c, key);
            return;
        }
        key.add(m.getClass().getName());
        key.add(m.getName());
        Simulator s = m.getSimulator();
        key.add(s.getLastEventTime());
        key.add(s.getNextEventTime());
        if (m instanceof StateKeys.ParaStateKey) { key.add(canonical(((StateKeys.ParaStateKey) m).paraStateKey())); return; }
        for (Field f : fields(m.getClass())) {
            try { key.add(f.getName()); key.add(canonical(f.get(m))); }
            catch (IllegalAccessException e) { key.add(new Object()); }
        }
    }

    private static Field[] fields(Class<?> cls) {
        Field[] r = FIELDS.get(cls);
        if (r != null) return r;
        List<Field> l = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != AtomicModelImpl.class && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || structural(f.getType())) continue;
                f.setAccessible(true);
                l.add(f);
            }
        r = l.toArray(new Field[0]);
        FIELDS.put(cls, r);
        return r;
    }

    private static boolean structural(Class<?> t) {
        return Port.class.isAssignableFrom(t) || Simulator.class.isAssignableFrom(t)
            || AtomicModel.class.isAssignableFrom(t) || PropertyChangeSupport.class.isAssignableFrom(t)
            || t.getName().startsWith("com.ms4systems.devs.helpers.");
    }

    private static Object cloneArray(Object a) {
        int n = Array.getLength(a);
        Object c = Array.newInstance(a.getClass().getComponentType(), n);
        System.arraycopy(a, 0, c, 0, n);
        return c;
    }

    static Object canonical(Object v) {
        if (v == null || v instanceof Number || v instanceof String || v instanceof Boolean
            || v instanceof Character || v instanceof Enum) return v;
        if (v.getClass().isArray() && v.getClass().getComponentType().isPrimitive()) return new Prim(cloneArray(v));
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
        Boolean eq = EQUALS.get(v.getClass());
        if (eq == null) {
            try { eq = v.getClass().getMethod("equals", Object.class).getDeclaringClass() != Object.class; }
            catch (NoSuchMethodException e) { eq = false; }
            EQUALS.put(v.getClass(), eq);
        }
        if (eq) return v;
        if (v instanceof Serializable) return new Snapshots.Bytes(Snapshots.save((Serializable) v));
        return new Object();
    }
}
