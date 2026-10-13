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
 * Moves the vessel from the channel entry to its berth. Ticks every sim-second but
 * COMPLETES on elapsed sim-time (so a test drives it by advancing the SimClock).
 * On arrival it reports {@code docked} to the HarbourMaster and swaps itself for
 * {@link DockAndServiceBehaviour}.
 *
 * <p><b>S16 (2026-10-13, ADR-20): this behaviour exists only while the ship is really
 * moving.</b> Before S16 it started at the berth grant and ran a fixed two sim-minutes, so the
 * ship was at its berth hours before the tugs the HarbourMaster believed were escorting it.
 * Now it is started by {@code BaseVesselAgent.onProceedToBerth} — the HarbourMaster's one
 * {@code proceed_to_berth} order, sent when the escort is on station, customs has cleared the
 * ship, the berth is open and the movement gate finds the weather safe — and it lasts as long
 * as the escort leg does: the order carries the duration, computed by the HarbourMaster from
 * the same distance and speed the tugs use, so ship and tugs arrive together.
 *
 * <p>ADR-17's sliding deadline is gone with it. A ship that is held while under way does not
 * freeze in mid-channel with its escort recalled: the vessel removes this behaviour and is back
 * at the channel entry ({@code BaseVesselAgent.onHold}); when it is released it starts the leg
 * afresh, with its new escort. Nothing here reads a "held" flag any more.
 */
public final class TransitToBerthBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(TransitToBerthBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L;       // 1 sim-second

    /**
     * The leg's length when an order carries none (a hand-built order in a test harness): the
     * pre-S16 two sim-minutes. Production orders always carry the escort leg's own duration.
     */
    public static final long DEFAULT_TRANSIT_SIM_MILLIS = 120_000L;

    private final long arriveBySimMillis;

    /** A fresh leg of {@code transitSimMillis}, starting now. */
    public TransitToBerthBehaviour(Agent agent, SimClock simClock, long transitSimMillis) {
        this(agent, simClock, Long.valueOf(simClock.nowSimMillis()
                + (transitSimMillis > 0 ? transitSimMillis : DEFAULT_TRANSIT_SIM_MILLIS)));
    }

    /** Task 22 resume: re-enter the transit with the SAVED absolute deadline, not a fresh leg. */
    public static TransitToBerthBehaviour resuming(Agent agent, SimClock simClock, long arriveBySimMillis) {
        return new TransitToBerthBehaviour(agent, simClock, Long.valueOf(arriveBySimMillis));
    }

    /** {@code arriveBy} is boxed only to keep this signature apart from the public "fresh leg" one. */
    private TransitToBerthBehaviour(Agent agent, SimClock simClock, Long arriveBy) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.arriveBySimMillis = arriveBy;
        // Task 22: report the flow position so the save snapshot knows where this vessel is.
        ((BaseVesselAgent) agent).reportPhase(BaseVesselAgent.FlowPhase.TRANSIT, arriveBySimMillis);
    }

    @Override
    protected void onSimTick() {
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        long now = simClock().nowSimMillis();
        Position p = vessel.position();
        vessel.setPosition(new Position(p.x() + 1.0, p.y(), p.headingDeg())); // cosmetic
        if (now >= arriveBySimMillis) {
            log.info("{} reached berth {} -> docking", myAgent.getLocalName(), vessel.assignedBerth());
            informDocked(vessel);
            vessel.transitEnded();
            myAgent.addBehaviour(new DockAndServiceBehaviour(myAgent, vessel.simClock()));
            myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
        }
    }

    /**
     * S16: the ship's OWN arrival report. Before it, the HarbourMaster learned of a docking only
     * from the tug's {@code escort_complete}, so a ship that needs no tug (every ferry) was never
     * DOCKED in its tracking at all — it read "in transit" for its whole stay.
     */
    private void informDocked(BaseVesselAgent vessel) {
        vessel.serviceLocator().findUnique("harbour-master").ifPresent(hm -> {
            ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
            inform.addReceiver(hm);
            inform.setContent(TerminalJson.write(Map.of(
                    "intent", "docked",
                    "vessel_id", vessel.spec().vesselId(),
                    "berth_id", vessel.assignedBerth() == null ? "" : vessel.assignedBerth())));
            myAgent.send(inform);
        });
    }
}
