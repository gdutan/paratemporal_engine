package examples.tutorial;

import com.ms4systems.devs.core.message.Port;
import com.ms4systems.devs.core.model.impl.CoupledModelImpl;

/**
 * Tutorial coupled model, equivalent to the SES
 *   ServiceLine is made of Arrivals and Server!
 *   Arrivals sends customer to Server!
 *   Server sends done to ServiceLine!
 */
public class ServiceLine extends CoupledModelImpl {
    private static final long serialVersionUID = 1L;
    public final Port<Integer> outDone;

    public ServiceLine(int customers) {
        super("ServiceLine");
        Arrivals arrivals = new Arrivals(customers);
        Server server = new Server();
        addChildModel(arrivals);
        addChildModel(server);
        outDone = addOutputPort("done", Integer.class);
        addCoupling(arrivals.outCustomer, server.inCustomer);
        addCoupling(server.outDone, outDone);
    }
}
