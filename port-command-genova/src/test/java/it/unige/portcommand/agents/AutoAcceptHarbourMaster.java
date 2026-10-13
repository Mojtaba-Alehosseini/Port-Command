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
 * Test HarbourMaster for the contracted-vessel IT: registers DF {@code harbour-master},
 * enqueues every message it receives, and auto-grants any REQUEST with an
 * ACCEPT_PROPOSAL carrying {@code {berth_id, price, hours}} (via {@link MessageFactory#reply}
 * so the conversation id + envelope are preserved).
 *
 * <p>args: {@code [BlockingQueue<ACLMessage> inbox, String berthId, Double price, Integer hours]}.
 */
public final class AutoAcceptHarbourMaster extends Agent {

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        BlockingQueue<ACLMessage> inbox = (BlockingQueue<ACLMessage>) getArguments()[0];
        String berthId = (String) getArguments()[1];
        double price = (Double) getArguments()[2];
        int hours = (Integer) getArguments()[3];
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
                if (msg.getPerformative() == ACLMessage.REQUEST) {
                    ACLMessage grant = MessageFactory.reply(msg, ACLMessage.ACCEPT_PROPOSAL);
                    grant.setContent(TerminalJson.write(Map.of(
                            "berth_id", berthId, "price", price, "hours", hours)));
                    myAgent.send(grant);
                    // S16 (ADR-20): a grant no longer starts the ship; the order to proceed does.
                    myAgent.send(order(msg, "proceed_to_berth"));
                } else if (msg.getPerformative() == ACLMessage.INFORM && msg.getContent() != null
                        && msg.getContent().contains("service_complete")) {
                    // S16: service_complete is the ship's request to leave; this puppet always agrees.
                    myAgent.send(order(msg, "cleared_to_depart"));
                }
            }
        });
    }

    /** A movement order to the sender of {@code from}, on the protocol tag vessels match. */
    private static ACLMessage order(ACLMessage from, String event) {
        ACLMessage order = MessageFactory.create(ACLMessage.INFORM);
        order.addReceiver(from.getSender());
        order.setProtocol(
                it.unige.portcommand.behaviours.auto_flow.HandleWeatherHoldBehaviour.VESSEL_WEATHER_PROTOCOL);
        order.setContent(TerminalJson.write(Map.of("event", event)));
        return order;
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
            throw new IllegalStateException("AutoAcceptHarbourMaster DF registration failed", e);
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
