package it.unige.portcommand.behaviours.negotiation;

import java.util.Map;
import java.util.Optional;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.artifacts.DealRecord;
import it.unige.portcommand.behaviours.auto_flow.AwaitBerthBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.ontology.Deal;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On a closed walk-in deal: CONFIRM to the HarbourMaster, record the deal in the
 * {@link it.unige.portcommand.artifacts.MarketHistoryArtifact} as {@link Deal.Outcome#DEAL},
 * then become "contracted" by attaching {@link AwaitBerthBehaviour} (the HM grants the
 * berth in task 11). The negotiation behaviours have already removed themselves.
 */
public final class DealClosedBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(DealClosedBehaviour.class);

    private final String conversationId;

    public DealClosedBehaviour(Agent agent, String conversationId) {
        super(agent);
        this.conversationId = conversationId;
    }

    @Override
    public void action() {
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        // Record BEFORE confirming so anyone observing the CONFIRM also sees the recorded deal.
        vessel.marketHistory().record(new DealRecord(
                vessel.spec().vesselType(), vessel.dealHours(), vessel.dealPrice(),
                vessel.simClock().nowSimMillis(), Deal.Outcome.DEAL));

        Optional<AID> hm = vessel.serviceLocator().findUnique("harbour-master");
        if (hm.isPresent()) {
            ACLMessage confirm = MessageFactory.create(ACLMessage.CONFIRM);
            confirm.addReceiver(hm.get());
            confirm.setConversationId(conversationId);
            confirm.setContent(TerminalJson.write(Map.of(
                    "intent", "deal_confirmed",
                    "price", vessel.dealPrice(),
                    "hours", vessel.dealHours())));
            myAgent.send(confirm);
        }

        myAgent.addBehaviour(new AwaitBerthBehaviour(myAgent, "berth-" + myAgent.getLocalName()));
        log.info("{}: deal closed at {} ({}h) -> CONFIRM + recorded + awaiting berth",
                myAgent.getLocalName(), vessel.dealPrice(), vessel.dealHours());
    }
}
