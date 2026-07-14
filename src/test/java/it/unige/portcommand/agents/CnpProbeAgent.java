package it.unige.portcommand.agents;

import java.util.concurrent.BlockingQueue;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

/**
 * Test puppet standing in for the HarbourMaster's CNP initiator. It drains an
 * {@code outbox} the test fills (messages the test wants sent, receivers already set)
 * and forwards everything it receives to an {@code inbox} the test polls. Because it
 * is the sender, the tugs' {@code createReply} PROPOSE/REFUSE/INFORM come straight
 * back to it.
 *
 * <p>args: {@code [BlockingQueue<ACLMessage> outbox, BlockingQueue<ACLMessage> inbox]}.
 */
public final class CnpProbeAgent extends Agent {

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        BlockingQueue<ACLMessage> outbox = (BlockingQueue<ACLMessage>) getArguments()[0];
        BlockingQueue<ACLMessage> inbox = (BlockingQueue<ACLMessage>) getArguments()[1];
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                boolean idle = true;
                ACLMessage toSend = outbox.poll();
                if (toSend != null) {
                    myAgent.send(toSend);
                    idle = false;
                }
                ACLMessage received = myAgent.receive();
                if (received != null) {
                    inbox.add(received);
                    idle = false;
                }
                if (idle) {
                    block(10); // poll the outbox ~every 10 ms; a real message arrival wakes us sooner
                }
            }
        });
    }
}
