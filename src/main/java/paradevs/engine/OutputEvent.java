package paradevs.engine;

import java.io.Serializable;
import java.util.Objects;

/** One physical output message of the top-level model: port name and value. */
public final class OutputEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String port;
    public final Object value;

    public OutputEvent(String port, Object value) { this.port = port; this.value = value; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof OutputEvent)) return false;
        OutputEvent e = (OutputEvent) o;
        return port.equals(e.port) && Objects.equals(value, e.value);
    }
    @Override public int hashCode() { return Objects.hash(port, value); }
    @Override public String toString() { return port + "=" + value; }
}
