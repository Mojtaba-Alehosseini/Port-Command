package it.unige.portcommand.agents;

import java.util.concurrent.BlockingQueue;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

/**
 * Generic test puppet for {@code HarbourMasterAgentIT}: optionally registers a DF
 * service (so the HarbourMaster can discover it as a tug/terminal/weather/customs
 * stand-in), drains an {@code outbox} the test fills (receivers already set) and
 * forwards everything it receives into an {@code inbox} the test polls. One class
 * plays every role across the scenarios — mirrors {@link CnpProbeAgent}'s
 * non-blocking poll/receive/block(10) shape (task 08) so it never starves its own
 * agent thread.
 *
 * <p>args: {@code [BlockingQueue<ACLMessage> outbox, BlockingQueue<ACLMessage> inbox,
 * String dfServiceType (nullable — omit for a vessel puppet, which the HarbourMaster
 * never discovers via DF, only via the sender AID on its own inbound REQUEST/PROPOSE)]}.
 */
public final class HmProbeAgent extends Agent {

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        Object[] args = getArguments();
        BlockingQueue<ACLMessage> outbox = (BlockingQueue<ACLMessage>) args[0];
        BlockingQueue<ACLMessage> inbox = (BlockingQueue<ACLMessage>) args[1];
        String dfServiceType = args.length > 2 ? (String) args[2] : null;
        if (dfServiceType != null) {
            registerService(dfServiceType);
        }
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
                    block(10);
                }
            }
        });
    }

    private void registerService(String type) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(type);
        sd.setName(getLocalName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            throw new IllegalStateException("HmProbeAgent DF registration failed", e);
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
