package sandbox.fastcopy;

import java.beans.PropertyChangeSupport;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.model.AtomicModel;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;
import com.ms4systems.devs.core.simulation.Simulator;

import paradevs.ms4.Snapshots;

/**
 * MEASUREMENT PROTOTYPE of the proposed verification mode. Compares a fast copy with a serialization
 * copy of the same parent, field by field, over the whole model tree, and fails with the path of the
 * first field that differs. Unlike the merge key it ignores ParaStateKey (a hand-written key can be as
 * incomplete as a hand-written copy), and it compares the MS4 event times.
 */
public final class Verify {
    private Verify() {}

    public static final class CopyMismatch extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public CopyMismatch(String m) { super(m); }
    }

    public static void sameState(AtomicModel fast, AtomicModel reference) { compare(fast, reference, reference.getName()); }

    private static void compare(AtomicModel a, AtomicModel b, String path) {
        if (a.getClass() != b.getClass()) fail(path, "class", a.getClass().getName(), b.getClass().getName());
        if (!Objects.equals(a.getName(), b.getName())) fail(path, "name", a.getName(), b.getName());
        if (a instanceof CoupledModelImpl) {
            List<AtomicModel> ca = ((CoupledModelImpl) a).getChildren(), cb = ((CoupledModelImpl) b).getChildren();
            if (ca.size() != cb.size()) fail(path, "children", ca.size(), cb.size());
            for (int i = 0; i < ca.size(); i++) compare(ca.get(i), cb.get(i), path + "/" + cb.get(i).getName());
        } else {
            Simulator sa = a.getSimulator(), sb = b.getSimulator();
            if (Double.compare(sa.getLastEventTime(), sb.getLastEventTime()) != 0) fail(path, "MS4 tL", sa.getLastEventTime(), sb.getLastEventTime());
            if (Double.compare(sa.getNextEventTime(), sb.getNextEventTime()) != 0) fail(path, "MS4 tN", sa.getNextEventTime(), sb.getNextEventTime());
        }
        for (Class<?> c = a.getClass(); c != null && c != AtomicModelImpl.class && c != CoupledModelImpl.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || structural(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object va = f.get(a), vb = f.get(b);
                    if (!same(va, vb)) fail(path, c.getSimpleName() + "." + f.getName(), show(va), show(vb));
                } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }
        }
    }

    private static boolean structural(Class<?> t) {
        return Port.class.isAssignableFrom(t) || Simulator.class.isAssignableFrom(t) || AtomicModel.class.isAssignableFrom(t)
            || PropertyChangeSupport.class.isAssignableFrom(t) || t.getName().startsWith("com.ms4systems.devs.helpers.");
    }

    private static boolean same(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getClass().isArray() || b.getClass().isArray()) return Objects.deepEquals(a, b);
        try { if (a.getClass().getMethod("equals", Object.class).getDeclaringClass() != Object.class) return a.equals(b); }
        catch (NoSuchMethodException e) { /* fall through */ }
        if (a instanceof Serializable && b instanceof Serializable)
            return Arrays.equals(Snapshots.save((Serializable) a), Snapshots.save((Serializable) b));
        return false;
    }

    private static String show(Object v) {
        if (v != null && v.getClass().isArray()) return Arrays.deepToString(new Object[]{v});
        return String.valueOf(v);
    }

    private static void fail(String path, String what, Object fast, Object ref) {
        throw new CopyMismatch("paraCopy() is incomplete at " + path + ": " + what + " is " + fast
            + " in the fast copy but " + ref + " in the serialization copy");
    }
}
