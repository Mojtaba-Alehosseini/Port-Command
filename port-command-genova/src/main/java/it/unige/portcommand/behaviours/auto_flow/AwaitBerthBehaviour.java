package it.unige.portcommand.behaviours.auto_flow;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.core.TerminalJson;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Waits for the HarbourMaster's berth decision on a given conversation id. On
 * ACCEPT_PROPOSAL it stores the granted {berth, price, hours} and transitions to
 * {@link TransitToBerthBehaviour}; on REFUSE it terminates (v1 — no retry).
 */
public final class AwaitBerthBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(AwaitBerthBehaviour.class);

    private final MessageTemplate template;

    public AwaitBerthBehaviour(Agent agent, String conversationId) {
        super(agent);
        this.template = MessageTemplate.and(
                MessageTemplate.MatchConversationId(conversationId),
                MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
                        MessageTemplate.MatchPerformative(ACLMessage.REFUSE)));
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(template);
        if (msg == null) {
            block();
            return;
        }
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        if (msg.getPerformative() == ACLMessage.REFUSE) {
            log.info("{}: berth REFUSED -> terminating (v1, no retry)", myAgent.getLocalName());
            myAgent.doDelete();
            return;
        }
        JsonNode grant = TerminalJson.readTreeOrNull(msg.getContent());
        String berthId = text(grant, "berth_id");
        double price = num(grant, "price");
        int hours = (int) num(grant, "hours");
        vessel.setDeal(berthId, price, hours);

        log.info("{}: berth GRANTED {} (price {}, {}h) -> transit", myAgent.getLocalName(), berthId, price, hours);
        myAgent.addBehaviour(new TransitToBerthBehaviour(myAgent, vessel.simClock()));
        myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }

    private static double num(JsonNode node, String field) {
        if (node == null) {
            return 0.0;
        }
        JsonNode v = node.get(field);
        return v == null ? 0.0 : v.asDouble();
    }
}
