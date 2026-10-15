package examples;

import java.util.Random;

import com.ms4systems.devs.core.simulation.Simulation;
import com.ms4systems.devs.core.simulation.impl.SimulationImpl;

import examples.baseball.BaseballGame;
import paradevs.Para;

/**
 * Plug-and-play check: the example models are ordinary MS4 models. Under MS4's own simulator,
 * with no engine active, Para.choose() samples, so this is a normal stochastic run.
 */
public class PlainMs4Run {
    public static void main(String[] args) {
        Para.setDefaultRandom(new Random(42));
        BaseballGame game = new BaseballGame(BaseballGame.NORMAL, BaseballGame.SCORING, 9);
        Simulation sim = new SimulationImpl("plain MS4 run", game);
        sim.startSimulation(0);
        sim.simulateIterations(Long.MAX_VALUE);
        System.out.println("Plain MS4 simulation of BaseballGame finished; last event at t="
            + game.getCoordinator().getLastEventTime());
    }
}
