package it.unige.portcommand.behaviours.coordination;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.prolog.PrologQueries;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Customs-side handler for clearance REQUESTs. Hazmat cargo → Prolog
 * {@code clearance_ok}: CONFIRM with a {@code CL-} ref, or INFORM(flagged) +
 * priority=high if blacklisted. Non-hazmat → a seeded roll against
 * {@code inspection_probability}: INFORM(routine_inspection) or CONFIRM. All replies
 * carry the envelope via {@link MessageFactory#reply}.
 */
public final class HandleClearanceRequestBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleClearanceRequestBehaviour.class);
    private static final int CLEARANCE_YEAR = 2026; // fixed (no wall clock); scenarios may override later
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY));

    private final Random customsRng;
    private final AtomicInteger refSeq;

    public HandleClearanceRequestBehaviour(Agent agent, Random customsRng, AtomicInteger refSeq) {
        super(agent);
        this.customsRng = customsRng;
        this.refSeq = refSeq;
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(TEMPLATE);
        if (msg == null) {
            block();
            return;
        }
        JsonNode req = TerminalJson.readTreeOrNull(msg.getContent());
        String vesselType = text(req, "vessel_type");
        String cargoClass = text(req, "cargo_class");
        if (vesselType == null || cargoClass == null) {
            flag(msg, "malformed");
            return;
        }
        log.info("clearance REQUEST {} / {} from {}", vesselType, cargoClass, msg.getSender().getLocalName());

        if (PrologQueries.isHazmat(cargoClass)) {
            if (PrologQueries.clearanceOk(vesselType, cargoClass)) {
                confirm(msg);
            } else {
                flag(msg, "blacklisted");
            }
        } else {
            double probability = PrologQueries.inspectionProbability(vesselType, cargoClass);
            if (customsRng.nextDouble() < probability) {
                routineInspection(msg);
            } else {
                confirm(msg);
            }
        }
    }

    private void confirm(ACLMessage original) {
        ACLMessage reply = MessageFactory.reply(original, ACLMessage.CONFIRM);
        reply.setContent(TerminalJson.write(
                Map.of("ref", "CL-" + CLEARANCE_YEAR + "-" + refSeq.getAndIncrement())));
        myAgent.send(reply);
        log.info("CONFIRM clearance -> {}", original.getSender().getLocalName());
    }

    private void flag(ACLMessage original, String reason) {
        ACLMessage reply = MessageFactory.reply(original, ACLMessage.INFORM);
        reply.setContent(TerminalJson.write(Map.of("event", "flagged", "reason", reason)));
        reply.addUserDefinedParameter("priority", "high");
        myAgent.send(reply);
        log.info("INFORM flagged ({}) -> {}", reason, original.getSender().getLocalName());
    }

    private void routineInspection(ACLMessage original) {
        ACLMessage reply = MessageFactory.reply(original, ACLMessage.INFORM);
        reply.setContent(TerminalJson.write(Map.of("event", "routine_inspection")));
        myAgent.send(reply);
        log.info("INFORM routine_inspection -> {}", original.getSender().getLocalName());
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null ? null : value.asText();
    }
}
