package it.unige.portcommand.behaviours.coordination;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.TerminalState;
import it.unige.portcommand.artifacts.BerthOccupancyUpdate;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emergency override: on an INFORM whose content is {@code event=flood, berth_id=X}
 * for a berth this terminal manages, clears the occupancy and DISCONFIRMs the
 * prior CONFIRM to the HarbourMaster. The terminal expects no other inbound INFORM,
 * so a non-flood INFORM is simply consumed and ignored.
 */
public final class RetractIfFloodBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(RetractIfFloodBehaviour.class);
    private static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY));

    private final TerminalState state;
    private final PortStateArtifact portState;
    private final ServiceLocator locator;

    public RetractIfFloodBehaviour(Agent agent, TerminalState state, PortStateArtifact portState,
                                   ServiceLocator locator) {
        super(agent);
        this.state = state;
        this.portState = portState;
        this.locator = locator;
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(TEMPLATE);
        if (msg == null) {
            block();
            return;
        }
        JsonNode node = TerminalJson.readTreeOrNull(msg.getContent());
        if (node == null || !"flood".equals(textOrNull(node, "event"))) {
            return; // not a flood notice — ignore
        }
        String berthId = textOrNull(node, "berth_id");
        if (berthId == null || !state.manages(berthId)) {
            return;
        }
        log.info("flood INFORM for berth {} — retracting occupancy", berthId);
        state.releaseBerth(berthId)
                .ifPresent(freed -> portState.update(new BerthOccupancyUpdate(berthId, freed)));
        disconfirmToHm(berthId);
    }

    private void disconfirmToHm(String berthId) {
        Optional<AID> hm = locator.findUnique("harbour-master");
        if (hm.isEmpty()) {
            log.warn("no harbour-master in DF; cannot DISCONFIRM flood retract for {}", berthId);
            return;
        }
        ACLMessage disconfirm = MessageFactory.create(ACLMessage.DISCONFIRM);
        disconfirm.addReceiver(hm.get());
        disconfirm.setContent(TerminalJson.write(Map.of("notice", "retracted", "berth_id", berthId)));
        myAgent.send(disconfirm);
        log.info("DISCONFIRM retracted berth={} -> harbour-master", berthId);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? null : value.asText();
    }
}
