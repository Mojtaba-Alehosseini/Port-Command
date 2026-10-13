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
 * INFORMs the HarbourMaster the service is complete — which since S16 (ADR-20) is also its
 * request to leave — waits at the berth for {@code cleared_to_depart}, and only then swaps
 * itself for {@link DepartBehaviour}. A test completes it by advancing the SimClock.
 */
public final class DockAndServiceBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(DockAndServiceBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L;
    private static final long SIM_MILLIS_PER_HOUR = 3_600_000L;

    private final long completeBySimMillis;
    /** S16: {@code service_complete} went out; from then on only the clearance is awaited. */
    private boolean departureRequested;

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
        if (!departureRequested) {
            departureRequested = true;
            Optional<AID> hm = vessel.serviceLocator().findUnique("harbour-master");
            if (hm.isEmpty()) {
                // No HarbourMaster in this container (a vessel-only harness): nobody to ask and
                // nobody to answer, so the ship leaves on its own as it always did.
                log.info("{} finished service ({}h) at {} -> depart (no harbour master to clear it)",
                        myAgent.getLocalName(), vessel.dealHours(), vessel.assignedBerth());
                vessel.onClearedToDepart();
            } else {
                ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
                inform.addReceiver(hm.get());
                inform.setContent(TerminalJson.write(Map.of(
                        "intent", "service_complete",
                        "vessel_id", vessel.spec().vesselId(),
                        "berth_id", vessel.assignedBerth() == null ? "" : vessel.assignedBerth())));
                myAgent.send(inform);
                log.info("{} finished service ({}h) at {} -> asking to depart", myAgent.getLocalName(),
                        vessel.dealHours(), vessel.assignedBerth());
            }
        }
        // S16 (ADR-20): a departure is a movement, and the HarbourMaster's gate decides every
        // movement. service_complete is the ship's request; it stays at its berth - which stays
        // occupied - until cleared_to_depart arrives. In safe weather that is the next message.
        if (!vessel.departureCleared()) {
            return;
        }
        log.info("{} cleared -> depart", myAgent.getLocalName());
        myAgent.addBehaviour(new DepartBehaviour(myAgent, vessel.simClock()));
        myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
    }
}
