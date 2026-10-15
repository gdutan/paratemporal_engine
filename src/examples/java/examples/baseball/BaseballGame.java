package examples.baseball;

import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;

/**
 * Coupled model Batter <-> BallGame:
 *   BaseballGame is made of Batter and BallGame!
 *   Batter sends hit to BallGame!   BallGame sends state to Batter!
 *   BallGame sends state to BaseballGame!
 */
public class BaseballGame extends CoupledModelImpl {
    private static final long serialVersionUID = 1L;
    public final Port<GameState> outState;

    public BaseballGame(double[][] normal, double[][] scoring, int plateAppearances) {
        super("BaseballGame");
        Batter batter = new Batter(normal, scoring, plateAppearances);
        BallGame game = new BallGame();
        addChildModel(batter);
        addChildModel(game);
        outState = addOutputPort("state", GameState.class);
        addCoupling(batter.outHit, game.inHit);
        addCoupling(game.outState, batter.inState);
        addCoupling(game.outState, outState);
    }

    /** Default lineup of BaseballDEVS_v7 RunHitToGame (p1..p9): YrOut, Single, Double, Triple, Homer, Walk. */
    public static final double[][] NORMAL = {
        {0.719, 0.1405, 0.0435, 0.0, 0.0268, 0.0702},      // Luis Campusano
        {0.6783, 0.1372, 0.0442, 0.0046, 0.0259, 0.1098},  // Jake Cronenworth
        {0.6933, 0.1857, 0.0324, 0.0022, 0.0238, 0.0626},  // Xander Bogaerts
        {0.6723, 0.1362, 0.0340, 0.0064, 0.0234, 0.1277},  // Ha-Seong Kim
        {0.675, 0.1617, 0.0467, 0.0, 0.0451, 0.0715},      // Manny Machado
        {0.6228, 0.1572, 0.0434, 0.0, 0.0359, 0.1407},     // Jurickson Profar
        {0.6762, 0.1703, 0.0523, 0.0101, 0.0405, 0.0506},  // Jackson Merrill
        {0.6599, 0.1530, 0.0479, 0.0023, 0.0479, 0.0890},  // Fernando Tatis Jr.
        {0.6547, 0.2396, 0.0476, 0.0045, 0.0060, 0.0476},  // Luis Arraez
    };
    /** Same batters with runners in scoring position. */
    public static final double[][] SCORING = {
        {0.6905, 0.1667, 0.0476, 0.0, 0.0357, 0.0595},
        {0.6648, 0.1538, 0.0440, 0.0055, 0.0220, 0.1099},
        {0.7083, 0.1875, 0.0313, 0.0, 0.0104, 0.0625},
        {0.6299, 0.1181, 0.0472, 0.0079, 0.0315, 0.1654},
        {0.6393, 0.1556, 0.0778, 0.0, 0.0444, 0.0833},     // sums to 1.0004 in the source
        {0.5764, 0.1667, 0.0347, 0.0, 0.0347, 0.1875},
        {0.7123, 0.1569, 0.0523, 0.0131, 0.0196, 0.0458},
        {0.7000, 0.1444, 0.0556, 0.0, 0.0222, 0.0778},
        {0.5983, 0.2951, 0.0410, 0.0, 0.0, 0.0656},
    };
}
