package examples.tutorial;

import java.util.List;
import java.util.Map;
import java.util.Random;

import com.ms4systems.devs.core.simulation.Simulation;
import com.ms4systems.devs.core.simulation.impl.SimulationImpl;

import examples.Check;
import paradevs.Para;
import paradevs.engine.InputTrace;
import paradevs.engine.OutputEvent;
import paradevs.engine.ParaEngine;
import paradevs.engine.ParaResult;
import paradevs.engine.ParaSpec;
import paradevs.engine.SelectionRule;
import paradevs.engine.Transducer;

/** Tutorial driver: every way of running the service-line model, checked against the hand answer. */
public class RunServiceLine {

    /** xi = number of customers served so far (a sufficient summary, so branches can merge). */
    static final Transducer<Integer> SERVED = new Transducer<Integer>() {
        @Override public Integer initial() { return 0; }
        @Override public Integer record(Integer served, double t, List<OutputEvent> outputs) {
            for (OutputEvent e : outputs) if (e.port.equals("done")) served++;
            return served;
        }
    };

    public static void main(String[] args) {
        Check check = new Check("Tutorial: service line");

        // Step 1: the atomic Server alone, with customers supplied by an input trace.
        InputTrace arrivals = InputTrace.builder()
            .at(1.0, "customer", 1).at(2.0, "customer", 2).at(3.0, "customer", 3).build();
        ParaResult<Integer> atomic = ParaEngine.run(ParaSpec.of(Server::new, SERVED).trace(arrivals));
        print("Step 1  Server + input trace", atomic);

        // Step 2: the coupled ServiceLine, which generates its own customers.
        ParaResult<Integer> coupled = ParaEngine.run(ParaSpec.of(() -> new ServiceLine(3), SERVED));
        print("Step 2  ServiceLine (coupled)", coupled);

        // Step 3: the same with safe merging.
        ParaResult<Integer> merged = ParaEngine.run(
            ParaSpec.of(() -> new ServiceLine(3), SERVED).merge(ParaSpec.Merge.SAFE));
        print("Step 3  ServiceLine, safe merge", merged);

        // Step 4: Monte Carlo with 2000 roots.
        ParaResult<Integer> mc = ParaEngine.run(ParaSpec.of(() -> new ServiceLine(3), SERVED)
            .selection(SelectionRule.monteCarlo()).roots(2000).seed(11));
        print("Step 4  ServiceLine, Monte Carlo N=2000", mc);

        // Step 5: an ordinary MS4 run (no engine): Para.choose samples one path.
        Para.setDefaultRandom(new Random(3));
        Simulation sim = new SimulationImpl("plain run", new ServiceLine(3));
        sim.startSimulation(0);
        sim.simulateIterations(Long.MAX_VALUE);

        // Hand answer: P(3 served) = 0.6 * 0.6 = 0.36, P(2 served) = 0.64.
        for (ParaResult<Integer> r : List.of(atomic, coupled, merged)) {
            Map<Integer, Double> law = r.marginal(x -> x);
            check.close("P(served=3)", law.getOrDefault(3, 0.0), 0.36, 1e-12);
            check.close("P(served=2)", law.getOrDefault(2, 0.0), 0.64, 1e-12);
            check.close("E[served]", r.expectation(x -> x), 2.36, 1e-12);
        }
        Map<Integer, Double> mlaw = mc.marginal(x -> x);
        check.close("MC P(served=3) within 4 s.e.", mlaw.getOrDefault(3, 0.0), 0.36, 4 * Math.sqrt(0.36 * 0.64 / 2000));
        check.finish();
    }

    static void print(String title, ParaResult<Integer> r) {
        System.out.println(title);
        for (Map.Entry<ParaResult.Outcome<Integer>, Double> e : r.distribution().entrySet())
            System.out.printf("    served=%d  status=%-7s  weight=%.4f%n", e.getKey().xi, e.getKey().status, e.getValue());
        System.out.printf("    E[served]=%.4f  mass=%.4f  %s%n", r.expectation(x -> x), r.totalMass(), r.stats());
    }
}
