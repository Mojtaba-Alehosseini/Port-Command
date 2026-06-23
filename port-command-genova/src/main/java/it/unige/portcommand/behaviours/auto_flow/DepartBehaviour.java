package it.unige.portcommand.behaviours.auto_flow;

import java.util.Map;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Animates the vessel out of the harbour over a short sim-interval, INFORMs the
 * HarbourMaster of departure, then {@code doDelete()}s the agent. A test completes it
 * by advancing the SimClock.
 */
public final class DepartBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(DepartBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L;
    private static final long DEPART_SIM_MILLIS = 60_000L; // ~1 sim-minute to clear the harbour

    private final long departedBySimMillis;
    private boolean informed = false;

    public DepartBehaviour(Agent agent, SimClock simClock) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.departedBySimMillis = simClock.nowSimMillis() + DEPART_SIM_MILLIS;
    }

    @Override
    protected void onSimTick() {
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        Position p = vessel.position();
        vessel.setPosition(new Position(p.x() - 1.0, p.y(), p.headingDeg())); // cosmetic
        if (simClock().nowSimMillis() < departedBySimMillis) {
            return;
        }
        if (!informed) {
            vessel.serviceLocator().findUnique("harbour-master").ifPresent(hm -> {
                ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
                inform.addReceiver(hm);
                inform.setContent(TerminalJson.write(Map.of(
                        "intent", "departed", "vessel_id", vessel.spec().vesselId())));
                myAgent.send(inform);
            });
            informed = true;
        }
        log.info("{} departed -> doDelete", myAgent.getLocalName());
        myAgent.doDelete();
    }
}
