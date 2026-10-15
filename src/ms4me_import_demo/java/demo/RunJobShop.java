package demo;

import java.util.List;
import java.util.Map;
import java.util.Random;

import com.ms4systems.devs.core.simulation.Simulation;
import com.ms4systems.devs.core.simulation.impl.SimulationImpl;

import Models.java.SimpleWorkFlow;
import paradevs.Para;
import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;
import paradevs.engine.Transducer;

/**
 * Runs the MS4 Me-generated JobProcessorExample (SimpleWorkFlow) under the paratemporal engine.
 * The generated sources are unchanged apart from two lines marked "ParaDEVS:".
 */
public class RunJobShop {

    /** xi = number of jobs solved so far. */
    static final Transducer<Integer> SOLVED = new Transducer<Integer>() {
        @Override public Integer initial() { return 0; }
        @Override public Integer record(Integer n, double t, List<OutputEvent> outs) {
            for (OutputEvent e : outs) if (e.port.equals("solved")) n++;
            return n;
        }
    };

    public static void main(String[] args) {
        // Stop each branch at its first event at or after t = 45.
        ParaSpec.Acceptor<Integer> byTime45 = (xi, t, n) -> t >= 45;

        ParaResult<Integer> exact = ParaEngine.run(ParaSpec.of(SimpleWorkFlow::new, SOLVED).acceptor(byTime45));
        print("Exhaustive", exact);

        ParaResult<Integer> merged = ParaEngine.run(ParaSpec.of(SimpleWorkFlow::new, SOLVED)
            .acceptor(byTime45).merge(ParaSpec.Merge.SAFE));
        print("Exhaustive + safe merge", merged);

        ParaResult<Integer> mc = ParaEngine.run(ParaSpec.of(SimpleWorkFlow::new, SOLVED)
            .acceptor(byTime45).selection(SelectionRule.monteCarlo()).roots(2000).seed(5));
        print("Monte Carlo N=2000", mc);

        // The same model still runs as an ordinary MS4 simulation.
        Para.setDefaultRandom(new Random(1));
        Simulation sim = new SimulationImpl("plain run", new SimpleWorkFlow());
        sim.startSimulation(0);
        sim.simulateIterations(200);
        System.out.println("Plain MS4 run completed 200 iterations.");
    }

    static void print(String title, ParaResult<Integer> r) {
        System.out.println(title + ": " + r.stats());
        for (Map.Entry<Integer, Double> e : new java.util.TreeMap<>(r.marginal(x -> x)).entrySet())
            System.out.printf("    solved by t=45: %d   P=%.4f%n", e.getKey(), e.getValue());
        System.out.printf("    E[solved]=%.4f  mass=%.4f%n", r.expectation(x -> x), r.totalMass());
    }
}
