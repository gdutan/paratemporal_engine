package sandbox.fastcopy;

import java.io.Externalizable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MEASUREMENT PROTOTYPE. A generic deep copy by reflection, meant to give the same object graph as a
 * serialize/deserialize round trip, without the byte stream:
 * <ul>
 * <li>immutable values (strings, boxed numbers, enums, classes) are shared;</li>
 * <li>arrays are copied element by element;</li>
 * <li>JDK collections (ArrayList, HashMap, LinkedHashMap, HashSet, LinkedHashSet, PriorityQueue) are
 *     rebuilt through their public API, so hash tables are rehashed for the copied keys (MS4's routing
 *     table is an identity-hashed HashMap keyed by ports);</li>
 * <li>other objects must be Serializable, with no custom writeObject/readObject/readResolve/writeReplace;
 *     they are allocated the way serialization does (no constructor run) and their non-static,
 *     non-transient fields are copied; transient fields are left at their defaults, as serialization does.</li>
 * </ul>
 * Anything else throws {@link Unsupported}, and the caller falls back to serialization.
 * Per-class plans (fields, allocator) are computed once and cached.
 */
final class ReflectCopy {

    static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Unsupported(String m) { super(m); }
    }

    private static final Map<Class<?>, Plan> PLANS = new ConcurrentHashMap<>();
    private final IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();

    private ReflectCopy() {}

    @SuppressWarnings("unchecked")
    static <T> T copy(T root) { return (T) new ReflectCopy().go(root); }

    private static boolean immutable(Class<?> c) {
        return c == String.class || c == Integer.class || c == Long.class || c == Double.class || c == Boolean.class
            || c == Character.class || c == Short.class || c == Byte.class || c == Float.class || c == Class.class
            || Enum.class.isAssignableFrom(c);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Object go(Object o) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        if (immutable(c)) return o;
        Object done = seen.get(o);
        if (done != null) return done;
        if (c.isArray()) {
            if (c.getComponentType().isPrimitive()) {
                Object a = cloneArray(o);
                seen.put(o, a);
                return a;
            }
            Object[] src = (Object[]) o;
            Object[] dst = (Object[]) Array.newInstance(c.getComponentType(), src.length);
            seen.put(o, dst);
            for (int i = 0; i < src.length; i++) dst[i] = go(src[i]);
            return dst;
        }
        if (c == ArrayList.class) {
            List<Object> src = (List<Object>) o, dst = new ArrayList<>(src.size());
            seen.put(o, dst);
            for (Object e : src) dst.add(go(e));
            return dst;
        }
        if (c == HashMap.class || c == LinkedHashMap.class) {
            Map<Object, Object> src = (Map<Object, Object>) o;
            Map<Object, Object> dst = c == HashMap.class ? new HashMap<>(Math.max(4, src.size() * 2)) : new LinkedHashMap<>(Math.max(4, src.size() * 2));
            seen.put(o, dst);
            for (Map.Entry<Object, Object> e : src.entrySet()) dst.put(go(e.getKey()), go(e.getValue()));
            return dst;
        }
        if (c == HashSet.class || c == LinkedHashSet.class) {
            Collection<Object> src = (Collection<Object>) o;
            Collection<Object> dst = c == HashSet.class ? new HashSet<>() : new LinkedHashSet<>();
            seen.put(o, dst);
            for (Object e : src) dst.add(go(e));
            return dst;
        }
        if (c == PriorityQueue.class) {
            PriorityQueue<Object> src = (PriorityQueue<Object>) o;
            PriorityQueue dst = new PriorityQueue<>(Math.max(1, src.size()), (Comparator) go(src.comparator()));
            seen.put(o, dst);
            for (Object e : src) dst.add(go(e));
            return dst;
        }
        return plan(c).copy(o, this);
    }

    private static Object cloneArray(Object a) {
        if (a instanceof int[]) return ((int[]) a).clone();
        if (a instanceof double[]) return ((double[]) a).clone();
        if (a instanceof long[]) return ((long[]) a).clone();
        if (a instanceof boolean[]) return ((boolean[]) a).clone();
        if (a instanceof byte[]) return ((byte[]) a).clone();
        if (a instanceof char[]) return ((char[]) a).clone();
        if (a instanceof float[]) return ((float[]) a).clone();
        return ((short[]) a).clone();
    }

    private static Plan plan(Class<?> c) {
        Plan p = PLANS.get(c);
        if (p == null) { p = new Plan(c); PLANS.put(c, p); }
        return p;
    }

    private static boolean jdk(Class<?> c) { return c.getName().startsWith("java.") || c.getName().startsWith("javax."); }

    /** How to copy one non-JDK class: an allocator plus its copyable fields. */
    private static final class Plan {
        final Constructor<?> alloc;
        final Field[] fields;
        final boolean arrayListBase;

        Plan(Class<?> c) {
            if (!Serializable.class.isAssignableFrom(c) || Externalizable.class.isAssignableFrom(c))
                throw new Unsupported(c.getName() + " is not plainly Serializable");
            List<Field> fs = new ArrayList<>();
            Class<?> k = c;
            for (; k != null && !jdk(k); k = k.getSuperclass()) {
                for (String m : new String[]{"writeObject", "readObject", "readResolve", "writeReplace", "readObjectNoData"})
                    for (java.lang.reflect.Method dm : k.getDeclaredMethods())
                        if (dm.getName().equals(m)) throw new Unsupported(k.getName() + " has custom serialization (" + m + ")");
                for (Field f : k.getDeclaredFields()) {
                    int mod = f.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                    f.setAccessible(true);
                    fs.add(f);
                }
            }
            if (k == null) k = Object.class;
            arrayListBase = k == ArrayList.class;
            if (k != Object.class && !arrayListBase) throw new Unsupported(c.getName() + " extends JDK class " + k.getName());
            fields = fs.toArray(new Field[0]);
            try {
                Constructor<?> base = arrayListBase ? ArrayList.class.getConstructor() : Object.class.getConstructor();
                alloc = sun.reflect.ReflectionFactory.getReflectionFactory().newConstructorForSerialization(c, base);
            } catch (NoSuchMethodException e) { throw new Unsupported(e.toString()); }
        }

        @SuppressWarnings("unchecked")
        Object copy(Object src, ReflectCopy rc) {
            Object dst;
            try { dst = alloc.newInstance(); }
            catch (ReflectiveOperationException e) { throw new Unsupported(e.toString()); }
            rc.seen.put(src, dst);
            try {
                for (Field f : fields) {
                    Class<?> t = f.getType();
                    if (t.isPrimitive()) {
                        if (t == double.class) f.setDouble(dst, f.getDouble(src));
                        else if (t == int.class) f.setInt(dst, f.getInt(src));
                        else if (t == boolean.class) f.setBoolean(dst, f.getBoolean(src));
                        else if (t == long.class) f.setLong(dst, f.getLong(src));
                        else f.set(dst, f.get(src));
                    } else {
                        f.set(dst, rc.go(f.get(src)));
                    }
                }
            } catch (IllegalAccessException e) { throw new Unsupported(e.toString()); }
            if (arrayListBase) for (Object e : (List<Object>) src) ((List<Object>) dst).add(rc.go(e));
            return dst;
        }
    }
}
