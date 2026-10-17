package paradevs.ms4;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Copy / Restore of a whole MS4 model graph (model, ports, simulators, children) by Java
 * serialization. A persistent branch state is stored as bytes, so copies share it for free,
 * and each re-run of a departure starts from an independent object graph.
 */
public final class Snapshots {
    private Snapshots() {}

    public static byte[] save(Serializable o) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(o);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Model state is not serializable: " + e.getMessage(), e);
        }
    }

    /**
     * Save a model that is attached to a running MS4 simulation. Its simulators refer to the
     * Simulation (thread pools, loggers), to event listeners and, at the top, to the enclosing
     * coordinator; none of these is model state. They are set aside while the model is serialized
     * and put back afterwards, so the running simulation is not changed. The copy has no simulation
     * attached, as every engine copy.
     */
    public static byte[] saveDetached(com.ms4systems.devs.core.model.AtomicModel model) {
        java.util.List<Object[]> undo = new java.util.ArrayList<>();
        try {
            detach(model, true, undo);
            return save(model);
        } finally {
            for (int i = undo.size() - 1; i >= 0; i--) {
                Object[] u = undo.get(i);
                try { ((java.lang.reflect.Field) u[0]).set(u[1], u[2]); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }
        }
    }

    private static void detach(com.ms4systems.devs.core.model.AtomicModel m, boolean top, java.util.List<Object[]> undo) {
        Object sim = m.getSimulator();
        if (sim instanceof com.ms4systems.devs.core.simulation.impl.SimulatorImpl) {
            clear(com.ms4systems.devs.core.simulation.impl.SimulatorImpl.class, sim, "simulation", null, undo);
            clear(com.ms4systems.devs.core.simulation.impl.SimulatorImpl.class, sim, "eventListeners", new java.util.ArrayList<>(), undo);
            if (top) clear(com.ms4systems.devs.core.simulation.impl.SimulatorImpl.class, sim, "parent", null, undo);
        }
        if (top) clear(com.ms4systems.devs.core.model.impl.AtomicModelImpl.class, m, "parent", null, undo);
        if (m instanceof com.ms4systems.devs.core.model.impl.CoupledModelImpl)
            for (com.ms4systems.devs.core.model.AtomicModel c : ((com.ms4systems.devs.core.model.impl.CoupledModelImpl) m).getChildren())
                detach(c, false, undo);
    }

    private static void clear(Class<?> owner, Object target, String field, Object value, java.util.List<Object[]> undo) {
        if (!owner.isInstance(target)) return;
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            undo.add(new Object[] {f, target, f.get(target)});
            f.set(target, value);
        } catch (NoSuchFieldException e) {
            // a different MS4 core version: nothing to set aside
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalStateException("Cannot detach " + field + " for a snapshot: " + e, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T restore(byte[] bytes) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (T) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Cannot restore model state: " + e.getMessage(), e);
        }
    }

    /** Value-equality wrapper for serialized bytes. */
    public static final class Bytes {
        private final byte[] b;
        private final int h;
        public Bytes(byte[] b) { this.b = b; this.h = Arrays.hashCode(b); }
        @Override public boolean equals(Object o) { return o instanceof Bytes && Arrays.equals(b, ((Bytes) o).b); }
        @Override public int hashCode() { return h; }
    }
}
