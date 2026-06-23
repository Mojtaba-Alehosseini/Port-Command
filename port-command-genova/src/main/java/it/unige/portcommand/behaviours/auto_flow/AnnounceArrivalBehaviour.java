package it.unige.portcommand.behaviours.auto_flow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contracted vessel's arrival announcement: a REQUEST to the HarbourMaster asking for
 * its berth, carrying the contract id and the full {@link it.unige.portcommand.ontology.VesselSpec}.
 * Then it attaches {@link AwaitBerthBehaviour} on the same conversation id.
 */
public final class AnnounceArrivalBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(AnnounceArrivalBehaviour.class);

    private final String contractId;
    private final String conversationId;

    public AnnounceArrivalBehaviour(Agent agent, String contractId) {
        super(agent);
        this.contractId = contractId;
        this.conversationId = "berth-" + agent.getLocalName();
    }

    @Override
    public void action() {
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        Optional<AID> harbourMaster = vessel.serviceLocator().findUnique("harbour-master");
        if (harbourMaster.isEmpty()) {
            log.warn("{}: no harbour-master in DF; cannot announce arrival", myAgent.getLocalName());
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("intent", "request_berth");
        content.put("contract", contractId == null ? "" : contractId);
        content.put("vessel_spec", vessel.spec());

        ACLMessage request = MessageFactory.create(ACLMessage.REQUEST);
        request.addReceiver(harbourMaster.get());
        request.setConversationId(conversationId);
        request.setContent(TerminalJson.write(content));
        myAgent.send(request);

        myAgent.addBehaviour(new AwaitBerthBehaviour(myAgent, conversationId));
        log.info("{} announced arrival (contract {}) -> harbour-master", myAgent.getLocalName(), contractId);
    }
}
