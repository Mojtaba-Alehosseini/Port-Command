package it.unige.portcommand.behaviours.coordination;

import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.core.MessageFactory;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aborts an in-progress escort on {@code CANCEL}: removes the active movement
 * behaviour (so the tug does NOT keep crawling toward a dead target — the "behaviour
 * leak" the task warns about), clears {@code currentJob}, and sends the tug back to
 * base via {@link ReturnToBaseBehaviour}.
 *
 * <p>This is a long-lived {@code CyclicBehaviour}, so it never calls
 * {@code removeBehaviour(this)} — it removes the <em>other</em> (movement) behaviour,
 * which is safe (only {@code removeBehaviour(this)} nulls the caller's {@code myAgent}).
 * A CANCEL for an already-idle tug is ignored.
 */
public final class HandleCancelBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleCancelBehaviour.class);
    public static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.CANCEL),
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY));

    /** ACCEPT_PROPOSALs only — the half of {@code HandleAcceptRejectBehaviour.TEMPLATE} a
     * CANCEL can legitimately overtake (see the race note in {@link #action}). */
    private static final MessageTemplate OVERTAKEN_ACCEPT = MessageTemplate.and(
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY),
            MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL));

    private final TugAgent tug;

    public HandleCancelBehaviour(TugAgent tug) {
        super(tug);
        this.tug = tug;
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(TEMPLATE);
        if (msg == null) {
            block();
            return;
        }
        String cancelledVessel = vesselIdOf(msg);
        if (tug.currentJob() == null && tug.activeMovement() == null) {
            // S10 (bug hunt, hunter B finding 2): receive() is template-selective, so this
            // CANCEL can OVERTAKE the very ACCEPT_PROPOSAL it cancels — both sit queued (the
            // HM sent ACCEPT then CANCEL within one storm pass) and the scheduler rotation ran
            // this cyclic first. Dropping the CANCEL as "idle" let the tug then honour the
            // stale ACCEPT and escort a vessel the port believes held — uncancellable (no
            // longer in the vessel's escort set) and the producer of a phantom
            // escort_complete. Honour the WIRE order instead: consume the queued ACCEPT and
            // discard it as cancelled-before-started. Deliberately no REFUSE — the normal
            // ordering (ACCEPT processed, then CANCEL aborts the leg) sends none and keeps
            // the charge (the hold path's stated no-refund design); both orderings now land
            // in the same state.
            // S11 (fix review, R1): the consume is KEYED — the hold CANCEL names its vessel,
            // and only that vessel's queued ACCEPT is discarded; any other award queued at
            // the same moment goes back untouched. The unkeyed form took whichever ACCEPT
            // sat first and could silently orphan an unrelated award.
            ACLMessage overtaken = consumeOvertakenAccept(cancelledVessel);
            if (overtaken != null) {
                tug.forgetBid(overtaken.getConversationId());
                log.info("{} CANCEL overtook the queued ACCEPT on CNP {} — award discarded "
                        + "before it started", tug.tugId(), overtaken.getConversationId());
                return;
            }
            log.debug("{} CANCEL while idle — nothing to abort", tug.tugId());
            return;
        }
        if (cancelledVessel != null && tug.currentJob() != null
                && !cancelledVessel.equals(tug.currentJob().vesselId())) {
            // S11 (fix review, R1): the CANCEL names a vessel this tug is NOT escorting — it
            // targets a queued award (the overtake race above, hit while busy with another
            // job), never the live escort. Aborting the live escort here would strand the
            // vessel actually under tow.
            ACLMessage overtaken = consumeOvertakenAccept(cancelledVessel);
            if (overtaken != null) {
                tug.forgetBid(overtaken.getConversationId());
                log.info("{} CANCEL for {} overtook its queued ACCEPT (busy with {}) — award "
                        + "discarded before it started", tug.tugId(), cancelledVessel,
                        tug.currentJob().vesselId());
            } else {
                log.debug("{} CANCEL for {} but escorting {} and no queued award matches — "
                        + "dropped", tug.tugId(), cancelledVessel, tug.currentJob().vesselId());
            }
            return;
        }
        log.info("{} CANCEL from {} -> abort, return to base",
                tug.tugId(), msg.getSender().getLocalName());

        Behaviour active = tug.activeMovement();
        if (active != null) {
            tug.removeBehaviour(active); // remove the *movement* behaviour, not this cyclic one
        }
        tug.clearJob();
        tug.setPickupTarget(null); // task 22: else a save after a CANCEL persists a stale pickup
        ReturnToBaseBehaviour ret = new ReturnToBaseBehaviour(tug);
        tug.setActiveMovement(ret);
        tug.setStatus(TugStatus.RETURNING);
        tug.addBehaviour(ret);
    }

    /**
     * Drains queued ACCEPT_PROPOSALs looking for {@code vesselId}'s award; every non-matching
     * message is put back in its original order ({@code putBack} inserts at the FRONT, hence
     * the reverse loop). A {@code null} vesselId — an unkeyed CANCEL from a producer that
     * predates the S11 key — falls back to the S10 behaviour: first queued ACCEPT wins.
     */
    private ACLMessage consumeOvertakenAccept(String vesselId) {
        java.util.List<ACLMessage> untouched = new java.util.ArrayList<>();
        ACLMessage match = null;
        ACLMessage m;
        while ((m = myAgent.receive(OVERTAKEN_ACCEPT)) != null) {
            if (vesselId == null || vesselId.equals(vesselIdOf(m))) {
                match = m;
                break;
            }
            untouched.add(m);
        }
        for (int i = untouched.size() - 1; i >= 0; i--) {
            tug.putBackMessage(untouched.get(i));
        }
        return match;
    }

    /** The {@code vessel_id} field of a message's JSON content, or {@code null} if absent. */
    private static String vesselIdOf(ACLMessage msg) {
        com.fasterxml.jackson.databind.JsonNode content =
                it.unige.portcommand.core.TerminalJson.readTreeOrNull(msg.getContent());
        return content != null && content.hasNonNull("vessel_id")
                ? content.get("vessel_id").asText()
                : null;
    }
}
