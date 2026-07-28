package it.unige.portcommand.behaviours.cnp;

import java.util.ArrayList;
import java.util.List;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.harbourmaster.CnpRequest;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-blocking bid collector for one Contract Net session: buffers every PROPOSE
 * (a bid) matching the exact {@code cfpId} until {@link #closeAndAward()} is called
 * by the paired {@code WakerBehaviour} deadline ({@link InitiateCNPBehaviour}).
 * Never calls {@code blockingReceive} — collecting bids and awarding the contract
 * happen on separate ticks of the agent's own scheduler, so every other
 * HarbourMaster behaviour (auto-flow dispatch, walk-in relay, emergency handling)
 * keeps running during the CFP window.
 */
public final class BidCollectorBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(BidCollectorBehaviour.class);
    private static final MessageTemplate TEMPLATE_BASE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(InitiateCNPBehaviour.CNP_PROTOCOL),
            MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                    MessageTemplate.MatchPerformative(ACLMessage.REFUSE)));

    private final CnpRequest req;
    private final MessageTemplate template;
    private final List<ACLMessage> bids = new ArrayList<>();

    public BidCollectorBehaviour(Agent agent, String cfpId, CnpRequest req) {
        super(agent);
        this.req = req;
        this.template = MessageTemplate.and(TEMPLATE_BASE, MessageTemplate.MatchConversationId(cfpId));
    }

    @Override
    public void action() {
        HarbourMasterAgent hm = (HarbourMasterAgent) myAgent;
        ACLMessage msg = hm.receiveLogged(template);
        if (msg == null) {
            block();
            return;
        }
        if (msg.getPerformative() == ACLMessage.PROPOSE) {
            bids.add(msg);
        }
        // REFUSE just isn't counted as a bid — no other action needed.
    }

    /**
     * Closes the collection window and hands the collected bids off for scoring.
     * Called exactly once, by the paired {@code WakerBehaviour}.
     */
    void closeAndAward() {
        List<ACLMessage> collected = List.copyOf(bids);
        myAgent.addBehaviour(new AwardContractBehaviour(myAgent, req, collected));
        log.info("CNP for {} closed with {} bid(s)", req.vesselId(), collected.size());
        myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
    }
}
