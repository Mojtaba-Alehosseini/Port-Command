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
 *
 * <p><b>B-01 (S8, ADR-17): the deadline SLIDES while the vessel is weather-held.</b> Each tick
 * taken under {@link BaseVesselAgent#weatherHeld()} pushes {@code arriveBySimMillis} forward by
 * exactly the sim-time that elapsed since the previous tick — transit progress is frozen (to
 * within one tick) for as long as the hold lasts, and a test-style clock JUMP while held slides
 * the whole jump. Nothing else changes: an unheld tick is the original countdown, and the save
 * snapshot keeps persisting {@code (TRANSIT, deadline)} — the slid deadline IS the suspension's
 * persisted form, which is why schema v1 needs no new field.
 */
public final class TransitToBerthBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(TransitToBerthBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L;       // 1 sim-second
    private static final long TRANSIT_SIM_MILLIS = 120_000L; // ~2 sim-minutes channel→berth

    private long arriveBySimMillis;
    private long lastTickSimMillis;

    public TransitToBerthBehaviour(Agent agent, SimClock simClock) {
        this(agent, simClock, simClock.nowSimMillis() + TRANSIT_SIM_MILLIS);
    }

    /** Task 22 resume: re-enter the transit with the SAVED absolute deadline, not a fresh leg. */
    public static TransitToBerthBehaviour resuming(Agent agent, SimClock simClock, long arriveBySimMillis) {
        return new TransitToBerthBehaviour(agent, simClock, arriveBySimMillis);
    }

    private TransitToBerthBehaviour(Agent agent, SimClock simClock, long arriveBySimMillis) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.arriveBySimMillis = arriveBySimMillis;
        this.lastTickSimMillis = simClock.nowSimMillis();
        // Task 22: report the flow position so the save snapshot knows where this vessel is.
        ((BaseVesselAgent) agent).reportPhase(BaseVesselAgent.FlowPhase.TRANSIT, arriveBySimMillis);
    }

    @Override
    protected void onSimTick() {
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        long now = simClock().nowSimMillis();
        if (vessel.weatherHeld()) {
            // Progress frozen: the elapsed slice moves the deadline, not the vessel. Re-reported
            // so a save taken mid-hold persists the CURRENT (slid) deadline (ADR-17).
            arriveBySimMillis += now - lastTickSimMillis;
            lastTickSimMillis = now;
            vessel.reportPhase(BaseVesselAgent.FlowPhase.TRANSIT, arriveBySimMillis);
            return;
        }
        lastTickSimMillis = now;
        Position p = vessel.position();
        vessel.setPosition(new Position(p.x() + 1.0, p.y(), p.headingDeg())); // cosmetic; GUI push is task 18
        if (now >= arriveBySimMillis) {
            log.info("{} reached berth {} -> docking", myAgent.getLocalName(), vessel.assignedBerth());
            myAgent.addBehaviour(new DockAndServiceBehaviour(myAgent, vessel.simClock()));
            myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
        }
    }
}
