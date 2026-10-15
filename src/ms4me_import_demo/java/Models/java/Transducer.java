/* Do not remove or modify this comment!  It is required for file identification!
DNL
platform:/resource/JobProcessorExample/src/Models/dnl/Transducer.dnl
356868734
 Do not remove or modify this comment!  It is required for file identification! */
package Models.java;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

import java.io.File;
import java.io.Serializable;

import java.net.URI;
import java.net.URISyntaxException;

import java.util.ArrayList;

// Custom library code
//ID:LIB:0
import java.util.HashSet;

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

//ENDID
// End custom library code
@SuppressWarnings("unused")
public class Transducer extends AtomicModelImpl implements PhaseBased,
    StateVariableBased {
    private static final long serialVersionUID = 1L;

    //ID:SVAR:0
    private static final int ID_JOBSARRIVED = 0;

    //ENDID
    //ID:SVAR:1
    private static final int ID_JOBSSOLVED = 1;

    //ENDID
    //ID:SVAR:2
    private static final int ID_OBSERVATIONTIME = 2;

    //ENDID
    //ID:SVAR:3
    private static final int ID_TOTALTA = 3;

    //ENDID
    //ID:SVAR:4
    private static final int ID_CLOCK = 4;

    // Declare state variables
    private PropertyChangeSupport propertyChangeSupport =
        new PropertyChangeSupport(this);
    protected HashSet<WorkToDo> jobsArrived;
    protected HashSet<WorkToDo> jobsSolved;
    protected double observationTime = 10000;
    protected double totalTa;
    protected double clock;

    //ENDID
    String phase = "observe";
    String previousPhase = null;
    Double sigma = 1000.0;
    Double previousSigma = Double.NaN;

    // End state variables

    // Input ports
    //ID:INP:0
    public final Port<WorkToDo> inAriv = addInputPort("inAriv", WorkToDo.class);

    //ENDID
    //ID:INP:1
    public final Port<WorkToDo> inSolved =
        addInputPort("inSolved", WorkToDo.class);

    //ENDID
    // End input ports

    // Output ports
    //ID:OUTP:0
    public final Port<String> outStop = addOutputPort("outStop", String.class);

    //ENDID
    // End output ports
    protected SimulationOptionsImpl options = new SimulationOptionsImpl();
    protected double currentTime;

    // This variable is just here so we can use @SuppressWarnings("unused")
    private final int unusedIntVariableForWarnings = 0;

    public Transducer() {
        this("Transducer");
    }

    public Transducer(String name) {
        this(name, null);
    }

    public Transducer(String name, Simulator simulator) {
        super(name, simulator);
    }

    public void initialize() {
        super.initialize();

        currentTime = 0;

        // Default state variable initialization
        observationTime = 10000;

        holdIn("observe", 1000.0);

        // Initialize Variables
        //ID:INIT
        jobsArrived = new HashSet<WorkToDo>();
        jobsSolved = new HashSet<WorkToDo>();
        //observationTime = 1000;
        totalTa = 0;
        clock = 0;
        holdIn("observe", observationTime);

        //ENDID
        // End initialize variables
    }

    @Override
    public void internalTransition() {
        currentTime += sigma;

        if (phaseIs("observe")) {
            getSimulator().modelMessage("Internal transition from observe");

            //ID:TRA:observe
            passivateIn("done");
            //ENDID
            // Internal event code
            //ID:INT:observe
            clock = clock + getTimeAdvance();
            double throughput;
            double avgTaTime;
            if (jobsSolved.size() > 0) {
                avgTaTime = totalTa / jobsSolved.size();
                if (clock > 0.0) {
                    throughput = jobsSolved.size() / clock;
                } else {
                    throughput = 0.0;
                }
            } else {
                avgTaTime = 0.0;
                throughput = 0.0;
            }
            System.out.println("End time: " + clock);
            System.out.println("jobs arrived : " + jobsArrived.size());
            System.out.println("jobs solved : " + jobsSolved.size());
            System.out.println("AVERAGE TA = " + avgTaTime);
            System.out.println("THROUGHPUT = " + throughput);

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
        if (phaseIs("observe")) {
            if (input.hasMessages(inAriv)) {
                ArrayList<Message<WorkToDo>> messageList =
                    inAriv.getMessages(input);

                // 'without reschedule' or 'eventually' used: not rescheduling events
                // Fire state and port specific external transition functions
                //ID:EXT:observe:inAriv
                clock = clock + timeElapsed;
                WorkToDo job = messageList.get(0).getData();
                job.setStartTime(clock);
                System.out.println("Start job " + job.getId() +
                    " @ startTime = " + clock);
                jobsArrived.add(job);

                //ENDID
                // End external event code
                return;
            }
            if (input.hasMessages(inSolved)) {
                ArrayList<Message<WorkToDo>> messageList =
                    inSolved.getMessages(input);

                // 'without reschedule' or 'eventually' used: not rescheduling events
                // Fire state and port specific external transition functions
                //ID:EXT:observe:inSolved
                clock = clock + timeElapsed;
                WorkToDo job = messageList.get(0).getData();
                WorkToDo arrived = null;
                for (Object o : jobsArrived) {
                    WorkToDo j = (WorkToDo) o;
                    if (j.getId() == job.getId()) {
                        arrived = j;
                        break;
                    }
                }
                totalTa += (clock - arrived.getStartTime());
                System.out.println("Finish job " + arrived.getId() +
                    " @ startTime = " + clock);

                job.setStartTime(clock);
                jobsSolved.add(job);

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

        if (phaseIs("observe")) {
            // Output event code
            //ID:OUT:observe
            output.add(outStop, "");

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
        Transducer model = new Transducer();
        model.options = options;

        if (options.isDisableViewer()) { // Command line output only
            Simulation sim =
                new SimulationImpl("Transducer Simulation", model, options);
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

    // Getter/setter for jobsArrived
    public void setJobsArrived(HashSet<WorkToDo> jobsArrived) {
        propertyChangeSupport.firePropertyChange("jobsArrived",
            this.jobsArrived, this.jobsArrived = jobsArrived);
    }

    public HashSet<WorkToDo> getJobsArrived() {
        return this.jobsArrived;
    }

    // End getter/setter for jobsArrived

    // Getter/setter for jobsSolved
    public void setJobsSolved(HashSet<WorkToDo> jobsSolved) {
        propertyChangeSupport.firePropertyChange("jobsSolved", this.jobsSolved,
            this.jobsSolved = jobsSolved);
    }

    public HashSet<WorkToDo> getJobsSolved() {
        return this.jobsSolved;
    }

    // End getter/setter for jobsSolved

    // Getter/setter for observationTime
    public void setObservationTime(double observationTime) {
        propertyChangeSupport.firePropertyChange("observationTime",
            this.observationTime, this.observationTime = observationTime);
    }

    public double getObservationTime() {
        return this.observationTime;
    }

    // End getter/setter for observationTime

    // Getter/setter for totalTa
    public void setTotalTa(double totalTa) {
        propertyChangeSupport.firePropertyChange("totalTa", this.totalTa,
            this.totalTa = totalTa);
    }

    public double getTotalTa() {
        return this.totalTa;
    }

    // End getter/setter for totalTa

    // Getter/setter for clock
    public void setClock(double clock) {
        propertyChangeSupport.firePropertyChange("clock", this.clock,
            this.clock = clock);
    }

    public double getClock() {
        return this.clock;
    }

    // End getter/setter for clock

    // State variables
    public String[] getStateVariableNames() {
        return new String[] {
            "jobsArrived", "jobsSolved", "observationTime", "totalTa", "clock"
        };
    }

    public Object[] getStateVariableValues() {
        return new Object[] {
            jobsArrived, jobsSolved, observationTime, totalTa, clock
        };
    }

    public Class<?>[] getStateVariableTypes() {
        return new Class<?>[] {
            HashSet.class, HashSet.class, Double.class, Double.class,
            Double.class
        };
    }

    @SuppressWarnings("unchecked")
    public void setStateVariableValue(int index, Object value) {
        switch (index) {

            case ID_JOBSARRIVED:
                setJobsArrived((HashSet<WorkToDo>) value);
                return;

            case ID_JOBSSOLVED:
                setJobsSolved((HashSet<WorkToDo>) value);
                return;

            case ID_OBSERVATIONTIME:
                setObservationTime((Double) value);
                return;

            case ID_TOTALTA:
                setTotalTa((Double) value);
                return;

            case ID_CLOCK:
                setClock((Double) value);
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
            dirUri = Transducer.class.getResource(".").toURI();
            dir = new File(dirUri);
        } catch (URISyntaxException e) {
            e.printStackTrace();
            throw new RuntimeException(
                "Could not find Models directory. Invalid model URL: " +
                Transducer.class.getResource(".").toString());
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
        return new String[] { "observe", "done" };
    }
}
