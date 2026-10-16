package sandbox.fastcopy;

import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.model.AtomicModel;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;

import examples.baseball.BallGame;
import examples.baseball.Batter;
import examples.baseball.GameState;
import examples.rl.MdpWalker;

/**
 * MEASUREMENT PROTOTYPE. The example models with a hand-written {@link ParaCopyable#paraCopy()}.
 * Each is the unchanged example model plus a copy method of a few lines; this is the effort the hook
 * asks of a model author.
 */
public final class FastModels {
    private FastModels() {}

    /** MdpWalker plus paraCopy(): the state is four fields; the MDP tables are shared by name. */
    public static class FastMdpWalker extends MdpWalker implements ParaCopyable {
        private static final long serialVersionUID = 1L;
        public FastMdpWalker(String mdpName, int startState, int steps, Mode mode) { super(mdpName, startState, steps, mode); }

        @Override public AtomicModel paraCopy() {
            FastMdpWalker c = new FastMdpWalker(mdpName, startState, steps, mode);
            c.s = s; c.left = left; c.done = done; c.sigma = sigma;
            return c;
        }
    }

    /** A deliberately incomplete copy (forgets {@code left}), to show that the verification mode catches it. */
    public static class BrokenMdpWalker extends MdpWalker implements ParaCopyable {
        private static final long serialVersionUID = 1L;
        public BrokenMdpWalker(String mdpName, int startState, int steps, Mode mode) { super(mdpName, startState, steps, mode); }

        @Override public AtomicModel paraCopy() {
            BrokenMdpWalker c = new BrokenMdpWalker(mdpName, startState, steps, mode);
            c.s = s; c.done = done; c.sigma = sigma;   // BUG (on purpose): c.left is not copied
            return c;
        }
    }

    /**
     * Another deliberate bug: the copy SHARES a mutable array (visit counts) instead of copying it. Each
     * copy starts with the right values, so a check at copy time passes; running the copy then changes
     * the frozen parent and every later sibling replay.
     */
    public static class AliasingMdpWalker extends MdpWalker implements ParaCopyable {
        private static final long serialVersionUID = 1L;
        protected int[] visits = new int[64];
        public AliasingMdpWalker(String mdpName, int startState, int steps, Mode mode) { super(mdpName, startState, steps, mode); }

        @Override public void internalTransition() { super.internalTransition(); visits[s]++; }

        @Override public AtomicModel paraCopy() {
            AliasingMdpWalker c = new AliasingMdpWalker(mdpName, startState, steps, mode);
            c.s = s; c.left = left; c.done = done; c.sigma = sigma;
            c.visits = visits;   // BUG (on purpose): should be visits.clone()
            return c;
        }
    }

    public static class FastBatter extends Batter {
        private static final long serialVersionUID = 1L;
        public FastBatter(double[][] normal, double[][] scoring, int pa) { super(normal, scoring, pa); }
        void copyFrom(FastBatter o) { phase = o.phase; index = o.index; left = o.left; hit = o.hit; sigma = o.sigma; }
        FastBatter fresh() { return new FastBatter(normal, scoring, plateAppearances); }   // shares the read-only tables
    }

    public static class FastBallGame extends BallGame {
        private static final long serialVersionUID = 1L;
        void copyFrom(FastBallGame o) {
            phase = o.phase; bases = o.bases; outs = o.outs; totalOuts = o.totalOuts; runs = o.runs; sigma = o.sigma;
        }
    }

    /** BaseballGame (same structure and couplings) built from the copyable children. */
    public static class FastBaseball extends CoupledModelImpl implements ParaCopyable {
        private static final long serialVersionUID = 1L;
        final FastBatter batter;
        final FastBallGame game;
        public final Port<GameState> outState;

        public FastBaseball(double[][] normal, double[][] scoring, int pa) { this(new FastBatter(normal, scoring, pa)); }

        private FastBaseball(FastBatter batter) {
            super("BaseballGame");
            this.batter = batter;
            this.game = new FastBallGame();
            addChildModel(batter);
            addChildModel(game);
            outState = addOutputPort("state", GameState.class);
            addCoupling(batter.outHit, game.inHit);
            addCoupling(game.outState, batter.inState);
            addCoupling(game.outState, outState);
        }

        @Override public AtomicModel paraCopy() {
            FastBaseball c = new FastBaseball(batter.fresh());
            c.batter.copyFrom(batter);
            c.game.copyFrom(game);
            return c;
        }
    }
}
