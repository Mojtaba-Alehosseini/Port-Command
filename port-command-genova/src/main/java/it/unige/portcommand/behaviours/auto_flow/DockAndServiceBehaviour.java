package it.unige.portcommand.behaviours.auto_flow;

import java.util.Map;
import java.util.Optional;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Occupies the berth for the deal's {@code dealHours} (measured in sim-time), then
 * INFORMs the HarbourMaster the service is complete and swaps itself for
 * {@link DepartBehaviour}. A test completes it by advancing the SimClock.
 */
public final class DockAndServiceBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(DockAndServiceBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L;
    private static final long SIM_MILLIS_PER_HOUR = 3_600_000L;

    private final long completeBySimMillis;

    public DockAndServiceBehaviour(Agent agent, SimClock simClock) {
        this(agent, simClock, simClock.nowSimMillis()
                + (long) ((BaseVesselAgent) agent).dealHours() * SIM_MILLIS_PER_HOUR);
    }

    /** Task 22 resume: keep serving until the SAVED deadline — hours already served stay served. */
    public static DockAndServiceBehaviour resuming(Agent agent, SimClock simClock, long completeBySimMillis) {
        return new DockAndServiceBehaviour(agent, simClock, completeBySimMillis);
    }

    private DockAndServiceBehaviour(Agent agent, SimClock simClock, long completeBySimMillis) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.completeBySimMillis = completeBySimMillis;
        ((BaseVesselAgent) agent).reportPhase(BaseVesselAgent.FlowPhase.DOCKED, completeBySimMillis);
    }

    @Override
    protected void onSimTick() {
        if (simClock().nowSimMillis() < completeBySimMillis) {
            return;
        }
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        Optional<AID> hm = vessel.serviceLocator().findUnique("harbour-master");
        if (hm.isPresent()) {
            ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
            inform.addReceiver(hm.get());
            inform.setContent(TerminalJson.write(Map.of(
                    "intent", "service_complete",
                    "vessel_id", vessel.spec().vesselId(),
                    "berth_id", vessel.assignedBerth() == null ? "" : vessel.assignedBerth())));
            myAgent.send(inform);
        }
        log.info("{} finished service ({}h) at {} -> depart", myAgent.getLocalName(),
                vessel.dealHours(), vessel.assignedBerth());
        myAgent.addBehaviour(new DepartBehaviour(myAgent, vessel.simClock()));
        myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
    }
}
