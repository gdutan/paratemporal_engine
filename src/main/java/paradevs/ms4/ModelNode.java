package paradevs.ms4;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ms4systems.devs.core.message.Coupling;
import com.ms4systems.devs.core.message.Message;
import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.AtomicModel;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;
import com.ms4systems.devs.core.simulation.Simulator;

/**
 * Drives an MS4 model (atomic or coupled) one Parallel DEVS event at a time.
 *
 * Atomic models are run through their own MS4 {@code SimulatorImpl}, so model code that calls
 * getSimulator() behaves exactly as under MS4. Coupled models are run by {@link Coupled}, a
 * coordinator that visits children and couplings in their declared list order. MS4's
 * CoordinatorImpl routes through identity-keyed HashMaps, so its child order can change between
 * copies of the same model. A replayed departure must make the same draws in the same order,
 * which requires a fixed order.
 */
public abstract class ModelNode {

    /** Build the node tree for {@code model}. */
    public static ModelNode of(AtomicModel model) {
        if (model instanceof CoupledModelImpl) return new Coupled((CoupledModelImpl) model);
        return new Atomic(model);
    }

    public abstract AtomicModel model();
    /** Run initialize() and set the event times (MS4 only calls initialize() at time 0). */
    public abstract void init(double t);
    /** Absolute time of the next internal event. */
    public abstract double tN();
    /** Output on this model's own output ports at {@code t} (empty unless imminent). */
    public abstract List<Message<?>> output(double t);
    /**
     * Internal, external, or confluent transition at {@code t}. {@code input} holds messages on
     * this model's input ports (empty for an internal event). Call output(t) first when imminent.
     */
    public abstract void transition(double t, MessageBag input);

    // ------------------------------------------------------------------ atomic

    static final class Atomic extends ModelNode {
        private final AtomicModel model;
        private final Simulator sim;
        Atomic(AtomicModel model) { this.model = model; this.sim = model.getSimulator(); }

        @Override public AtomicModel model() { return model; }
        @Override public void init(double t) { sim.initialize(t); }
        @Override public double tN() { return sim.getNextEventTime(); }

        @Override public List<Message<?>> output(double t) {
            List<Message<?>> out = new ArrayList<>();
            if (Double.compare(sim.getNextEventTime(), t) != 0) return out;
            MessageBag bag = sim.computeOutput();
            if (bag != null) out.addAll(bag);
            return out;
        }

        @Override public void transition(double t, MessageBag input) {
            if (input == null || input.isEmpty()) sim.executeNextEvent(t);
            else sim.processInput(t, input);   // external, or confluent when t == tN
            sim.clearCachedOutput();
        }
    }

    // ------------------------------------------------------------------ coupled

    static final class Coupled extends ModelNode {
        private final CoupledModelImpl model;
        private final List<ModelNode> children = new ArrayList<>();
        private final Map<AtomicModel, ModelNode> byModel = new IdentityHashMap<>();
        private final List<Coupling> couplings;
        private double outputTime = Double.NaN;
        private Map<ModelNode, List<Message<?>>> childOutputs;

        Coupled(CoupledModelImpl model) {
            this.model = model;
            for (AtomicModel child : model.getChildren()) {
                ModelNode n = ModelNode.of(child);
                children.add(n);
                byModel.put(child, n);
            }
            this.couplings = new ArrayList<>(model.getCouplings());
        }

        @Override public AtomicModel model() { return model; }

        @Override public void init(double t) { for (ModelNode c : children) c.init(t); }

        @Override public double tN() {
            double m = Double.POSITIVE_INFINITY;
            for (ModelNode c : children) m = Math.min(m, c.tN());
            return m;
        }

        /** Outputs of the imminent children at t, computed once per t. */
        private Map<ModelNode, List<Message<?>>> imminentOutputs(double t) {
            if (Double.compare(outputTime, t) != 0 || childOutputs == null) {
                childOutputs = new LinkedHashMap<>();
                for (ModelNode c : children)
                    if (Double.compare(c.tN(), t) == 0) childOutputs.put(c, c.output(t));
                outputTime = t;
            }
            return childOutputs;
        }

        @Override public List<Message<?>> output(double t) {
            List<Message<?>> out = new ArrayList<>();
            if (Double.compare(tN(), t) != 0) return out;
            for (List<Message<?>> msgs : imminentOutputs(t).values())
                for (Message<?> m : msgs)
                    for (Coupling c : couplings)
                        if (c.getSourcePort() == m.getPort() && c.getDestinationPort().getModel() == model)
                            out.add(m.replicateOnPort(c.getDestinationPort()));   // EOC
            return out;
        }

        @Override public void transition(double t, MessageBag input) {
            Map<ModelNode, List<Message<?>>> imm =
                Double.compare(tN(), t) == 0 ? imminentOutputs(t) : new LinkedHashMap<>();
            Map<ModelNode, MessageBag> inbox = new LinkedHashMap<>();
            for (List<Message<?>> msgs : imm.values())
                for (Message<?> m : msgs) route(m, t, inbox);                  // IC
            if (input != null) for (Message<?> m : input) route(m, t, inbox);  // EIC
            for (ModelNode c : children) {
                MessageBag in = inbox.get(c);
                if (imm.containsKey(c) || in != null)
                    c.transition(t, in == null ? MessageBag.EMPTY : in);
            }
            childOutputs = null;
            outputTime = Double.NaN;
        }

        private void route(Message<?> m, double t, Map<ModelNode, MessageBag> inbox) {
            for (Coupling c : couplings) {
                if (c.getSourcePort() != m.getPort()) continue;
                Port<? extends Serializable> dest = c.getDestinationPort();
                if (dest.getModel() == model) continue;            // EOC, reported by output()
                ModelNode target = byModel.get(dest.getModel());
                if (target == null) throw new IllegalStateException("Coupling to unknown model " + dest.getModel().getName());
                MessageBag bag = inbox.computeIfAbsent(target, k -> { MessageBag b = new MessageBagImpl(); b.setMessageTime(t); return b; });
                bag.add(m.replicateOnPort(dest));
            }
        }
    }
}
