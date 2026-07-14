package it.unige.portcommand.behaviours.auto_flow;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves the vessel from the channel to its berth. Ticks every sim-second but
 * COMPLETES on elapsed sim-time (so a test drives it by advancing the SimClock).
 * On arrival it swaps itself for {@link DockAndServiceBehaviour}.
 */
public final class TransitToBerthBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(TransitToBerthBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L;       // 1 sim-second
    private static final long TRANSIT_SIM_MILLIS = 120_000L; // ~2 sim-minutes channel→berth

    private final long arriveBySimMillis;

    public TransitToBerthBehaviour(Agent agent, SimClock simClock) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.arriveBySimMillis = simClock.nowSimMillis() + TRANSIT_SIM_MILLIS;
    }

    @Override
    protected void onSimTick() {
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        Position p = vessel.position();
        vessel.setPosition(new Position(p.x() + 1.0, p.y(), p.headingDeg())); // cosmetic; GUI push is task 18
        if (simClock().nowSimMillis() >= arriveBySimMillis) {
            log.info("{} reached berth {} -> docking", myAgent.getLocalName(), vessel.assignedBerth());
            myAgent.addBehaviour(new DockAndServiceBehaviour(myAgent, vessel.simClock()));
            myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
        }
    }
}
