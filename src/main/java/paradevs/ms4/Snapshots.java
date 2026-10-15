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
