/* Do not remove or modify this comment!  It is required for file identification!
DNL
platform:/resource/JobProcessorExample/src/Models/dnl/GeneratorOfJobs.dnl
298777845
 Do not remove or modify this comment!  It is required for file identification! */
package Models.java;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

import java.io.File;
import java.io.Serializable;

import java.net.URI;
import java.net.URISyntaxException;

import java.util.ArrayList;

import com.ms4systems.devs.core.message.Message;
import com.ms4systems.devs.core.message.MessageBag;
import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.message.impl.MessageBagImpl;
import com.ms4systems.devs.core.model.impl.AtomicModelImpl;
import com.ms4systems.devs.core.simulation.Simulation;
import com.ms4systems.devs.core.simulation.Simulator;
import com.ms4systems.devs.core.simulation.impl.SimulationImpl;
import com.ms4systems.devs.extensions.PhaseBased;
import com.ms4systems.devs.extensions.StateVariableBased;
import com.ms4systems.devs.helpers.impl.SimulationOptionsImpl;
import com.ms4systems.devs.simviewer.standalone.SimViewer;

@SuppressWarnings("unused")
public class GeneratorOfJobs extends AtomicModelImpl implements PhaseBased,
    StateVariableBased {
    private static final long serialVersionUID = 1L;

    //ID:SVAR:0
    private static final int ID_COUNT = 0;

    //ENDID
    //ID:SVAR:1
    private static final int ID_NUMBEROFJOBS = 1;

    //ENDID
    //ID:SVAR:2
    private static final int ID_WTD = 2;

    // Declare state variables
    private PropertyChangeSupport propertyChangeSupport =
        new PropertyChangeSupport(this);
    protected int count = 0;
    protected int numberOfJobs = 50;
    protected WorkToDo wtd;

    //ENDID
    String phase = "generate";
    String previousPhase = null;
    Double sigma = 10.0;
    Double previousSigma = Double.NaN;

    // End state variables

    // Input ports
    //ID:INP:0
    public final Port<String> inStop = addInputPort("inStop", String.class);

    //ENDID
    // End input ports

    // Output ports
    //ID:OUTP:0
    public final Port<WorkToDo> outJob =
        addOutputPort("outJob", WorkToDo.class);

    //ENDID
    // End output ports
    protected SimulationOptionsImpl options = new SimulationOptionsImpl();
    protected double currentTime;

    // This variable is just here so we can use @SuppressWarnings("unused")
    private final int unusedIntVariableForWarnings = 0;

    public GeneratorOfJobs() {
        this("GeneratorOfJobs");
    }

    public GeneratorOfJobs(String name) {
        this(name, null);
    }

    public GeneratorOfJobs(String name, Simulator simulator) {
        super(name, simulator);
    }

    public void initialize() {
        super.initialize();

        currentTime = 0;

        // Default state variable initialization
        count = 0;
        numberOfJobs = 50;

        holdIn("generate", 10.0);

        // Initialize Variables
        //ID:INIT
        wtd = new WorkToDo();
        wtd.setId(count);
        // ParaDEVS: random processing time (was: wtd.setProcessingTime(20 + count);)
        wtd.setProcessingTime(paradevs.Para.choose("processingTime",
            paradevs.Law.of(java.util.Arrays.asList(5.0, 15.0), new double[] {0.7, 0.3})));

        //ENDID
        // End initialize variables
    }

    @Override
    public void internalTransition() {
        currentTime += sigma;

        if (phaseIs("generate")) {
            getSimulator().modelMessage("Internal transition from generate");

            //ID:TRA:generate
            holdIn("generate", 10.0);
            //ENDID
            // Internal event code
            //ID:INT:generate
            count++;

            if (count >= numberOfJobs) {
                passivateIn("passive");
            } else {
                wtd = new WorkToDo();
                wtd.setId(count);
                // ParaDEVS: random processing time (was: wtd.setProcessingTime(20 + count);)
                wtd.setProcessingTime(paradevs.Para.choose("processingTime",
                    paradevs.Law.of(java.util.Arrays.asList(5.0, 15.0), new double[] {0.7, 0.3})));
            }

            //ENDID
            // End internal event code
            return;
        }

        //passivate();
    }

    @Override
    public void externalTransition(double timeElapsed, MessageBag input) {
        currentTime += timeElapsed;
        // Subtract time remaining until next internal transition (no effect if sigma == Infinity)
        sigma -= timeElapsed;

        // Store prior data
        previousPhase = phase;
        previousSigma = sigma;

        // Fire state transition functions
        if (phaseIs("generate")) {
            if (input.hasMessages(inStop)) {
                ArrayList<Message<String>> messageList =
                    inStop.getMessages(input);

                passivateIn("passive");

                // Fire state and port specific external transition functions
                //ID:EXT:generate:inStop

                //ENDID
                // End external event code
                return;
            }
        }
    }

    @Override
    public void confluentTransition(MessageBag input) {
        // confluentTransition with internalTransition first (by default)
        internalTransition();
        externalTransition(0, input);
    }

    @Override
    public Double getTimeAdvance() {
        return sigma;
    }

    @Override
    public MessageBag getOutput() {
        MessageBag output = new MessageBagImpl();

        if (phaseIs("generate")) {
            // Output event code
            //ID:OUT:generate
            output.add(outJob, wtd);

            //ENDID
            // End output event code
        }
        return output;
    }

    // Custom function definitions

    // End custom function definitions
    public static void main(String[] args) {
        SimulationOptionsImpl options = new SimulationOptionsImpl(args, true);

        // Uncomment the following line to disable SimViewer for this model
        // options.setDisableViewer(true);

        // Uncomment the following line to disable plotting for this model
        // options.setDisablePlotting(true);

        // Uncomment the following line to disable logging for this model
        // options.setDisableLogging(true);
        GeneratorOfJobs model = new GeneratorOfJobs();
        model.options = options;

        if (options.isDisableViewer()) { // Command line output only
            Simulation sim =
                new SimulationImpl("GeneratorOfJobs Simulation", model, options);
            sim.startSimulation(0);
            sim.simulateIterations(Long.MAX_VALUE);
        } else { // Use SimViewer
            SimViewer viewer = new SimViewer();
            viewer.open(model, options);
        }
    }

    public void addPropertyChangeListener(String propertyName,
        PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(propertyName, listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(listener);
    }

    // Getter/setter for count
    public void setCount(int count) {
        propertyChangeSupport.firePropertyChange("count", this.count,
            this.count = count);
    }

    public int getCount() {
        return this.count;
    }

    // End getter/setter for count

    // Getter/setter for numberOfJobs
    public void setNumberOfJobs(int numberOfJobs) {
        propertyChangeSupport.firePropertyChange("numberOfJobs",
            this.numberOfJobs, this.numberOfJobs = numberOfJobs);
    }

    public int getNumberOfJobs() {
        return this.numberOfJobs;
    }

    // End getter/setter for numberOfJobs

    // Getter/setter for wtd
    public void setWtd(WorkToDo wtd) {
        propertyChangeSupport.firePropertyChange("wtd", this.wtd, this.wtd = wtd);
    }

    public WorkToDo getWtd() {
        return this.wtd;
    }

    // End getter/setter for wtd

    // State variables
    public String[] getStateVariableNames() {
        return new String[] { "count", "numberOfJobs", "wtd" };
    }

    public Object[] getStateVariableValues() {
        return new Object[] { count, numberOfJobs, wtd };
    }

    public Class<?>[] getStateVariableTypes() {
        return new Class<?>[] { Integer.class, Integer.class, WorkToDo.class };
    }

    public void setStateVariableValue(int index, Object value) {
        switch (index) {

            case ID_COUNT:
                setCount((Integer) value);
                return;

            case ID_NUMBEROFJOBS:
                setNumberOfJobs((Integer) value);
                return;

            case ID_WTD:
                setWtd((WorkToDo) value);
                return;

            default:
                return;
        }
    }

    // Convenience functions
    protected void passivate() {
        passivateIn("passive");
    }

    protected void passivateIn(String phase) {
        holdIn(phase, Double.POSITIVE_INFINITY);
    }

    protected void holdIn(String phase, Double sigma) {
        this.phase = phase;
        this.sigma = sigma;
        getSimulator()
            .modelMessage("Holding in phase " + phase + " for time " + sigma);
    }

    protected static File getModelsDirectory() {
        URI dirUri;
        File dir;
        try {
            dirUri = GeneratorOfJobs.class.getResource(".").toURI();
            dir = new File(dirUri);
        } catch (URISyntaxException e) {
            e.printStackTrace();
            throw new RuntimeException(
                "Could not find Models directory. Invalid model URL: " +
                GeneratorOfJobs.class.getResource(".").toString());
        }
        boolean foundModels = false;
        while (dir != null && dir.getParentFile() != null) {
            if (dir.getName().equalsIgnoreCase("java") &&
                  dir.getParentFile().getName().equalsIgnoreCase("models")) {
                return dir.getParentFile();
            }
            dir = dir.getParentFile();
        }
        throw new RuntimeException(
            "Could not find Models directory from model path: " +
            dirUri.toASCIIString());
    }

    protected static File getDataFile(String fileName) {
        return getDataFile(fileName, "txt");
    }

    protected static File getDataFile(String fileName, String directoryName) {
        File modelDir = getModelsDirectory();
        File dir = new File(modelDir, directoryName);
        if (dir == null) {
            throw new RuntimeException("Could not find '" + directoryName +
                "' directory from model path: " + modelDir.getAbsolutePath());
        }
        File dataFile = new File(dir, fileName);
        if (dataFile == null) {
            throw new RuntimeException("Could not find '" + fileName +
                "' file in directory: " + dir.getAbsolutePath());
        }
        return dataFile;
    }

    protected void msg(String msg) {
        getSimulator().modelMessage(msg);
    }

    // Phase display
    public boolean phaseIs(String phase) {
        return this.phase.equals(phase);
    }

    public String getPhase() {
        return phase;
    }

    public String[] getPhaseNames() {
        return new String[] { "generate", "passive" };
    }
}
