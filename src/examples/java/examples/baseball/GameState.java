package examples.baseball;

import java.io.Serializable;
import java.util.Objects;

/** BallGame report: base configuration, outs in the inning, total outs, runs. Immutable. */
public final class GameState implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String bases;
    public final int outs, totalOuts, runs;

    public GameState(String bases, int outs, int totalOuts, int runs) {
        this.bases = bases; this.outs = outs; this.totalOuts = totalOuts; this.runs = runs;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof GameState)) return false;
        GameState g = (GameState) o;
        return bases.equals(g.bases) && outs == g.outs && totalOuts == g.totalOuts && runs == g.runs;
    }
    @Override public int hashCode() { return Objects.hash(bases, outs, totalOuts, runs); }
    @Override public String toString() { return bases + " outs=" + outs + " totalOuts=" + totalOuts + " runs=" + runs; }
}
