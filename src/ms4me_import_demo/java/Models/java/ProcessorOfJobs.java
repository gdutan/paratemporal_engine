/* Do not remove or modify this comment!  It is required for file identification!
DNL
platform:/resource/JobProcessorExample/src/Models/dnl/ProcessorOfJobs.dnl
-1503135235
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
public class ProcessorOfJobs extends AtomicModelImpl implements PhaseBased,
    StateVariableBased {
    private static final long serialVersionUID = 1L;

    //ID:SVAR:0
    private static final int ID_COUNT = 0;

    //ENDID
    //ID:SVAR:1
    private static final int ID_STOREDJOB = 1;

    // Declare state variables
    private PropertyChangeSupport propertyChangeSupport =
        new PropertyChangeSupport(this);
    protected int count = 0;
    protected WorkToDo storedJob = new WorkToDo();

    //ENDID
    String phase = "waitForJob";
    String previousPhase = null;
    Double sigma = Double.POSITIVE_INFINITY;
    Double previousSigma = Double.NaN;

    // End state variables

    // Input ports
    //ID:INP:0
    public final Port<WorkToDo> inJob = addInputPort("inJob", WorkToDo.class);

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

    public ProcessorOfJobs() {
        this("ProcessorOfJobs");
    }

    public ProcessorOfJobs(String name) {
        this(name, null);
    }

    public ProcessorOfJobs(String name, Simulator simulator) {
        super(name, simulator);
    }

    public void initialize() {
        super.initialize();

        currentTime = 0;

        // Default state variable initialization
        count = 0;
        storedJob = new WorkToDo();

        passivateIn("waitForJob");

    }

    @Override
    public void internalTransition() {
        currentTime += sigma;

        if (phaseIs("sendJob")) {
            getSimulator().modelMessage("Internal transition from sendJob");

            //ID:TRA:sendJob
            passivateIn("waitForJob");

            //ENDID
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
        if (phaseIs("waitForJob")) {
            if (input.hasMessages(inJob)) {
                ArrayList<Message<WorkToDo>> messageList =
                    inJob.getMessages(input);

                holdIn("sendJob", 50.0);
                // Fire state and port specific external transition functions
                //ID:EXT:waitForJob:inJob
                storedJob = messageList.get(0).getData();
                holdIn("sendJob", storedJob.getProcessingTime());

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

        if (phaseIs("sendJob")) {
            // Output event code
            //ID:OUT:sendJob
            output.add(outJob, storedJob);

            //ENDID
            // End output event code
        }
        return output;
    }

    // Custom function definitions

    //ID:CUST:0

    //ENDID

    // End custom function definitions
    public static void main(String[] args) {
        SimulationOptionsImpl options = new SimulationOptionsImpl(args, true);

        // Uncomment the following line to disable SimViewer for this model
        // options.setDisableViewer(true);

        // Uncomment the following line to disable plotting for this model
        // options.setDisablePlotting(true);

        // Uncomment the following line to disable logging for this model
        // options.setDisableLogging(true);
        ProcessorOfJobs model = new ProcessorOfJobs();
        model.options = options;

        if (options.isDisableViewer()) { // Command line output only
            Simulation sim =
                new SimulationImpl("ProcessorOfJobs Simulation", model, options);
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

    // Getter/setter for storedJob
    public void setStoredJob(WorkToDo storedJob) {
        propertyChangeSupport.firePropertyChange("storedJob", this.storedJob,
            this.storedJob = storedJob);
    }

    public WorkToDo getStoredJob() {
        return this.storedJob;
    }

    // End getter/setter for storedJob

    // State variables
    public String[] getStateVariableNames() {
        return new String[] { "count", "storedJob" };
    }

    public Object[] getStateVariableValues() {
        return new Object[] { count, storedJob };
    }

    public Class<?>[] getStateVariableTypes() {
        return new Class<?>[] { Integer.class, WorkToDo.class };
    }

    public void setStateVariableValue(int index, Object value) {
        switch (index) {

            case ID_COUNT:
                setCount((Integer) value);
                return;

            case ID_STOREDJOB:
                setStoredJob((WorkToDo) value);
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
            dirUri = ProcessorOfJobs.class.getResource(".").toURI();
            dir = new File(dirUri);
        } catch (URISyntaxException e) {
            e.printStackTrace();
            throw new RuntimeException(
                "Could not find Models directory. Invalid model URL: " +
                ProcessorOfJobs.class.getResource(".").toString());
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
        return new String[] { "waitForJob", "sendJob" };
    }
}
