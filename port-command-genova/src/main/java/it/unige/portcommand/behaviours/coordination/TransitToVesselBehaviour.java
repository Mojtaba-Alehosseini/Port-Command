package it.unige.portcommand.behaviours.coordination;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugJob;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.ontology.Position;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First escort leg: moves the tug from its current position to the vessel pickup
 * point. A sim-time ticker — each tick advances by the sim-seconds elapsed on the
 * {@link it.unige.portcommand.util.SimClock} since the previous tick, so a driver
 * advances the tug purely by advancing the clock (no wall sleeps). Because the step
 * uses the same {@link it.unige.portcommand.agents.TugMath} conversion as the bid
 * ETA, arrival coincides with the quoted {@code eta_minutes}.
 *
 * <p><b>S16 (2026-10-13, ADR-20): the tug WAITS at the ship.</b> Before S16 it started the
 * escort leg the moment it reached the pickup — on its own, whatever the ship, its co-winning
 * tug, the weather or customs were doing — while the ship had sailed in by itself hours earlier.
 * Now it reports {@code tug_on_station} to the HarbourMaster and stays there
 * ({@link TugStatus#ON_STATION}) until the HarbourMaster orders the escort to begin: one
 * {@code begin_escort} INFORM, sent in the same action as the ship's {@code proceed_to_berth},
 * and only when the whole escort is on station, customs has cleared the ship, its berth is open
 * and the movement gate says the weather is safe. Ship and tugs then make the channel leg
 * together.
 *
 * <p>The order is matched by protocol and must carry THIS job's Contract Net conversation id. A
 * tug that was cancelled and later re-hired for the same vessel may still have the old order in
 * its queue; without the id it would start the new escort on a stale permission.
 *
 * <p>The wait needs no behaviour of its own (the behaviour catalogue is locked): once arrived,
 * each tick only polls for the order. A CANCEL while waiting is {@code HandleCancelBehaviour}'s
 * as before — this behaviour is still the tug's {@code activeMovement}, so the cancel removes it
 * and sends the tug home. A tug restored from a save as ON_STATION re-enters here with its own
 * position as the pickup: it "arrives" on the first tick and reports again, which is how the
 * rebuilt HarbourMaster learns who is on station (it does not persist that).
 *
 * <p>On the order it hands off to {@link EscortToBerthBehaviour}: do the work + add the
 * next behaviour FIRST, {@code removeBehaviour(this)} LAST (that call nulls
 * {@code myAgent}; anything after it NPEs — INVARIANTS.md).
 */
public final class TransitToVesselBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(TransitToVesselBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L; // sample the clock each sim-second

    /** Tags the HarbourMaster's {@code begin_escort} order to a tug waiting on station (S16). */
    public static final String ESCORT_ORDER_PROTOCOL = "port-command-escort-order";

    public static final MessageTemplate BEGIN_ESCORT_TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(ESCORT_ORDER_PROTOCOL),
            MessageTemplate.MatchPerformative(ACLMessage.INFORM));

    private final TugAgent tug;
    private final Position pickup;
    private long lastSimMillis;
    /** S16: arrived at the ship, reported, waiting for {@code begin_escort}. */
    private boolean onStation;

    public TransitToVesselBehaviour(TugAgent tug, Position pickup) {
        super(tug, tug.simClock(), TICK_SIM_MILLIS);
        this.tug = tug;
        this.pickup = pickup;
        this.lastSimMillis = tug.simClock().nowSimMillis();
    }

    @Override
    protected void onSimTick() {
        if (onStation) {
            awaitBeginEscort();
            return;
        }
        long now = simClock().nowSimMillis();
        double dtSimSeconds = (now - lastSimMillis) / 1000.0;
        lastSimMillis = now;
        if (dtSimSeconds <= 0.0) {
            return; // clock hasn't advanced since last tick — nothing to move
        }
        boolean arrived = tug.advanceToward(pickup, dtSimSeconds);
        tug.pushState();
        if (arrived) {
            onStation = true;
            tug.setPickupTarget(null); // task 22: the EN_ROUTE leg is over; nothing to resume
            tug.setStatus(TugStatus.ON_STATION);
            informOnStation();
            log.info("{} reached vessel pickup {} -> on station, waiting for the escort order",
                    tug.tugId(), pickup);
        }
    }

    /** Tells the HarbourMaster this tug has reached its ship — once per arrival. */
    private void informOnStation() {
        TugJob job = tug.currentJob();
        if (job == null || job.client() == null) {
            return;
        }
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(job.client());
        if (job.conversationId() != null) {
            inform.setConversationId(job.conversationId()); // the order comes back on this id
        }
        inform.setContent(TerminalJson.write(Map.of(
                "event", "tug_on_station", "vessel_id", String.valueOf(job.vesselId()))));
        tug.send(inform);
    }

    /**
     * Polls for the HarbourMaster's order. An order for another job (a different conversation id:
     * left over from an escort that was cancelled) is consumed and dropped, never obeyed.
     */
    private void awaitBeginEscort() {
        TugJob job = tug.currentJob();
        ACLMessage order;
        while ((order = tug.receive(BEGIN_ESCORT_TEMPLATE)) != null) {
            if (job == null || job.conversationId() == null
                    || !job.conversationId().equals(order.getConversationId())) {
                JsonNode content = TerminalJson.readTreeOrNull(order.getContent());
                log.debug("{} dropped a stale escort order ({}) — not for its current job",
                        tug.tugId(), content);
                continue;
            }
            log.info("{} ordered to begin the escort of {} -> escorting to berth",
                    tug.tugId(), job.vesselId());
            tug.setStatus(TugStatus.ESCORTING);
            EscortToBerthBehaviour escort = new EscortToBerthBehaviour(tug);
            tug.setActiveMovement(escort);
            tug.addBehaviour(escort);
            tug.removeBehaviour(this); // LAST: nulls myAgent
            return;
        }
    }
}
