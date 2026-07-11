package it.unige.portcommand.behaviours.cnp;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugMath;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.ontology.Position;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tug side of the Contract Net: answers every {@code CFP} from the HarbourMaster
 * with a {@code PROPOSE} (if available) or a {@code REFUSE} (task 08 §8.2). The
 * REFUSE is emitted inline here — {@code RefuseAssignmentBehaviour} stays a parked
 * stub (task-07 precedent).
 *
 * <p>Bid maths route entirely through {@link TugMath}: pixels→km at the single
 * boundary, then knots→km/h, so the quoted {@code eta_minutes} matches what the
 * transit leg will actually take. Every reply goes through {@link MessageFactory#reply}
 * (preserves conversation-id + in-reply-to, re-stamps the {@code port_command_v1}/{@code json}
 * envelope).
 */
public final class BidInCNPBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(BidInCNPBehaviour.class);
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.CFP),
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY));

    private final TugAgent tug;

    public BidInCNPBehaviour(TugAgent tug) {
        super(tug);
        this.tug = tug;
    }

    @Override
    public void action() {
        ACLMessage cfp = myAgent.receive(TEMPLATE);
        if (cfp == null) {
            block();
            return;
        }

        JsonNode content = TerminalJson.readTreeOrNull(cfp.getContent());
        Position pickup = position(content, "target_vessel_position");
        if (pickup == null) {
            refuse(cfp, "malformed");
            return;
        }
        if (tug.currentJob() != null) {
            refuse(cfp, "busy");
            return;
        }
        if (tug.fuelState() < TugAgent.LOW_FUEL_THRESHOLD) {
            refuse(cfp, "low_fuel");
            return;
        }

        double distanceKm = TugMath.distanceKm(tug.position(), pickup);
        double cost = tug.baseFare() + tug.fuelCostPerKm() * distanceKm;
        double etaMinutes = TugMath.etaMinutes(distanceKm, tug.topSpeedKnots());

        String conversationId = cfp.getConversationId();
        tug.rememberBid(conversationId, pickup);
        warnIfLate(content, conversationId);

        Map<String, Object> bid = new LinkedHashMap<>();
        bid.put("cost", cost);
        bid.put("eta_minutes", etaMinutes);
        bid.put("fuel_state", tug.fuelState());
        bid.put("position", tug.position());
        ACLMessage propose = MessageFactory.reply(cfp, ACLMessage.PROPOSE);
        propose.setContent(TerminalJson.write(bid));
        myAgent.send(propose);
        log.info("{} PROPOSE cost={} eta={}min fuel={} -> {}",
                tug.tugId(), round(cost), round(etaMinutes), tug.fuelState(),
                cfp.getSender().getLocalName());
    }

    private void refuse(ACLMessage cfp, String reason) {
        ACLMessage reply = MessageFactory.reply(cfp, ACLMessage.REFUSE);
        reply.setContent(TerminalJson.write(Map.of("reason", reason)));
        myAgent.send(reply);
        log.info("{} REFUSE {} -> {}", tug.tugId(), reason, cfp.getSender().getLocalName());
    }

    /**
     * Best-effort late-bid WARN against a sim-millisecond {@code reply_by} deadline
     * carried in the CFP content (task 08: the tug still replies, but a missed
     * deadline is logged). Read from the SimClock, never the wall clock.
     */
    private void warnIfLate(JsonNode content, String conversationId) {
        if (content == null || !content.hasNonNull("reply_by") || tug.simClock() == null) {
            return;
        }
        long replyBySimMillis = content.get("reply_by").asLong();
        if (tug.simClock().nowSimMillis() > replyBySimMillis) {
            log.warn("{} bidding past reply_by on CNP {} (sim now {} > {})",
                    tug.tugId(), conversationId, tug.simClock().nowSimMillis(), replyBySimMillis);
        }
    }

    /** Extracts a nested {x, y, heading_deg?} object as a {@link Position}, or {@code null} if absent/blank. */
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

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
