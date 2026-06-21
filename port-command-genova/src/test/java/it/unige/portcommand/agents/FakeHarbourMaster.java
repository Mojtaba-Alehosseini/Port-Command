package it.unige.portcommand.agents;

import java.util.concurrent.BlockingQueue;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;

/**
 * Test stand-in for the HarbourMaster: registers DF {@code harbour-master} (so a
 * terminal's {@code findUnique("harbour-master")} resolves to it) and queues every
 * message it receives, so the test can assert the terminal's INFORM/DISCONFIRM.
 *
 * <p>args: {@code [BlockingQueue<ACLMessage> inbox]}.
 */
public final class FakeHarbourMaster extends Agent {

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        BlockingQueue<ACLMessage> inbox = (BlockingQueue<ACLMessage>) getArguments()[0];
        registerHarbourMaster();
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage m = myAgent.receive();
                if (m == null) {
                    block();
                    return;
                }
                inbox.add(m);
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
            throw new IllegalStateException("fake HM DF registration failed", e);
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException ignored) {
            // best-effort cleanup
        }
    }
}
