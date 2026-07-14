package it.unige.portcommand.scaffold;

import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trivial smoke-test agent. Registers a {@code smoke-test} service in the
 * Directory Facilitator, logs once, then deletes itself. No game logic — this
 * exists only to prove the JADE agent lifecycle works (task 01).
 */
public class SmokeAgent extends Agent {

    private static final Logger log = LoggerFactory.getLogger(SmokeAgent.class);

    @Override
    protected void setup() {
        log.info("SmokeAgent {} up", getLocalName());
        registerWithDf();
        addBehaviour(new OneShotBehaviour(this) {
            @Override
            public void action() {
                myAgent.doDelete();
            }
        });
    }

    private void registerWithDf() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("smoke-test");
        sd.setName("smoke");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            throw new IllegalStateException("DF registration failed", e);
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException ignored) {
            // Agent is terminating; a failed deregister is not actionable here.
        }
        log.info("SmokeAgent {} down", getLocalName());
    }
}
