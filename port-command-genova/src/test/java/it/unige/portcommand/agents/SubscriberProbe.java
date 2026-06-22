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
 * Test agent: registers a given DF service type (so the weather agent's
 * {@code findAll}/{@code findUnique} resolves it) and queues every message it
 * receives, so the test can assert broadcasts / threshold alerts.
 *
 * <p>args: {@code [String serviceType, BlockingQueue<ACLMessage> inbox]}.
 */
public final class SubscriberProbe extends Agent {

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        String serviceType = (String) getArguments()[0];
        BlockingQueue<ACLMessage> inbox = (BlockingQueue<ACLMessage>) getArguments()[1];
        register(serviceType);
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

    private void register(String serviceType) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        sd.setName(getLocalName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            throw new IllegalStateException("SubscriberProbe DF registration failed for " + serviceType, e);
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
