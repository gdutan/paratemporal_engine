/* Do not remove or modify this comment!  It is required for file identification!
DNL
platform:/resource/JobProcessorExample/src/Models/dnl/GeneratorOfJobs.dnl
 Do not remove or modify this comment!  It is required for file identification! */
package Models.java;

import java.io.Serializable;

public class WorkToDo implements Serializable {
    private static final long serialVersionUID = 1L;

    //ID:VAR:WorkToDo:0
    int id;

    //ENDIF
    //ID:VAR:WorkToDo:1
    double startTime;

    //ENDIF
    //ID:VAR:WorkToDo:2
    double processingTime;

    //ENDIF
    public WorkToDo() {
    }

    public WorkToDo(int id, double startTime, double processingTime) {
        this.id = id;
        this.startTime = startTime;
        this.processingTime = processingTime;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getId() {
        return this.id;
    }

    public void setStartTime(double startTime) {
        this.startTime = startTime;
    }

    public double getStartTime() {
        return this.startTime;
    }

    public void setProcessingTime(double processingTime) {
        this.processingTime = processingTime;
    }

    public double getProcessingTime() {
        return this.processingTime;
    }

    public String toString() {
        String str = "WorkToDo";
        str += "\n\tid: " + this.id;
        str += "\n\tstartTime: " + this.startTime;
        str += "\n\tprocessingTime: " + this.processingTime;
        return str;
    }
}
