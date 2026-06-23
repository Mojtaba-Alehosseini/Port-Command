package it.unige.portcommand.agents;

import java.util.Map;
import java.util.concurrent.BlockingQueue;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;

/**
 * Test HarbourMaster for the walk-in IT (plays the player relay): registers DF
 * {@code harbour-master}, enqueues every message it receives, and answers each PROPOSE
 * from the vessel with a counter PROPOSE carrying {@code {price, hours, berth_id}} (the
 * {@code berth_id} is a Prolog-verified compatible berth). The vessel's mocked engine
 * decides when to accept; non-PROPOSE messages (ACCEPT_PROPOSAL, CONFIRM) are only
 * enqueued, not countered.
 *
 * <p>args: {@code [BlockingQueue<ACLMessage> inbox, Double counterPrice, Integer counterHours, String berthId]}.
 */
public final class CounterOfferHarbourMaster extends Agent {

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        BlockingQueue<ACLMessage> inbox = (BlockingQueue<ACLMessage>) getArguments()[0];
        double counterPrice = (Double) getArguments()[1];
        int counterHours = (Integer) getArguments()[2];
        String berthId = (String) getArguments()[3];
        registerHarbourMaster();
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive();
                if (msg == null) {
                    block();
                    return;
                }
                inbox.add(msg);
                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    ACLMessage counter = MessageFactory.reply(msg, ACLMessage.PROPOSE);
                    counter.setContent(TerminalJson.write(Map.of(
                            "price", counterPrice, "hours", counterHours, "berth_id", berthId)));
                    myAgent.send(counter);
                }
            }
        });
    }

    private void registerHarbourMaster() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("harbour-master");
        sd.setName(getLocalName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            throw new IllegalStateException("CounterOfferHarbourMaster DF registration failed", e);
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException ignored) {
            // best-effort
        }
    }
}
