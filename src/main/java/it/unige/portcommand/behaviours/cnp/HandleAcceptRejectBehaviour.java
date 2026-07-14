package it.unige.portcommand.behaviours.cnp;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugJob;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.behaviours.coordination.TransitToVesselBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.ontology.Position;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the HarbourMaster's Contract Net decision on a bid this tug made. On
 * {@code ACCEPT_PROPOSAL} it takes the job — sets {@code currentJob}, goes
 * {@code EN_ROUTE_TO_VESSEL}, and starts {@link TransitToVesselBehaviour} toward the
 * pickup it remembered for that conversation. On {@code REJECT_PROPOSAL} it forgets
 * the bid.
 *
 * <p>The escort destination ({@code berth_position}) rides in the ACCEPT — the
 * HarbourMaster is authoritative about which berth the vessel goes to. The pickup
 * comes from the tug's own remembered bid (keyed by conversation-id), so the tug
 * never reads a vessel agent's live state.
 */
public final class HandleAcceptRejectBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleAcceptRejectBehaviour.class);
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY),
            MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
                    MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)));

    private final TugAgent tug;

    public HandleAcceptRejectBehaviour(TugAgent tug) {
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
        String conversationId = msg.getConversationId();

        if (msg.getPerformative() == ACLMessage.REJECT_PROPOSAL) {
            tug.forgetBid(conversationId);
            log.debug("{} bid REJECTED on CNP {}", tug.tugId(), conversationId);
            return;
        }

        // ACCEPT_PROPOSAL
        Position pickup = tug.takeBid(conversationId);
        if (pickup == null) {
            log.warn("{} ACCEPT for unknown/expired CNP {} — ignoring", tug.tugId(), conversationId);
            return;
        }
        if (tug.currentJob() != null) {
            log.warn("{} ACCEPT on CNP {} but already committed to {} — declining",
                    tug.tugId(), conversationId, tug.currentJob().vesselId());
            return;
        }

        JsonNode content = TerminalJson.readTreeOrNull(msg.getContent());
        String vesselId = text(content, "vessel_id");
        Position berth = position(content, "berth_position");
        if (berth == null) {
            log.warn("{} ACCEPT on CNP {} carried no berth_position — escort will be a no-op leg",
                    tug.tugId(), conversationId);
            berth = pickup;
        }

        tug.setCurrentJob(new TugJob(vesselId, berth, msg.getSender(), conversationId));
        tug.clearPendingBids(); // now busy — any other outstanding bids are moot
        tug.setStatus(TugStatus.EN_ROUTE_TO_VESSEL);
        TransitToVesselBehaviour transit = new TransitToVesselBehaviour(tug, pickup);
        tug.setActiveMovement(transit);
        myAgent.addBehaviour(transit);
        log.info("{} ACCEPTED escort of {} on CNP {} -> transit to {}",
                tug.tugId(), vesselId, conversationId, pickup);
    }

    private static String text(JsonNode content, String field) {
        if (content == null) {
            return null;
        }
        JsonNode v = content.get(field);
        return v == null ? null : v.asText();
    }

    /** Extracts a nested {x, y, heading_deg?} object as a {@link Position}, or {@code null} if absent. */
    private static Position position(JsonNode content, String field) {
        if (content == null) {
            return null;
        }
        JsonNode node = content.get(field);
        if (node == null || !node.hasNonNull("x") || !node.hasNonNull("y")) {
            return null;
        }
        double heading = node.hasNonNull("heading_deg") ? node.get("heading_deg").asDouble() : 0.0;
        return new Position(node.get("x").asDouble(), node.get("y").asDouble(), heading);
    }
}
