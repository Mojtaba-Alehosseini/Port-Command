package it.unige.portcommand.behaviours.cnp;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub behaviour (task 05). The owning agent task fills the plan body; until then
 * the body logs once and does nothing else. No blocking spin, no JPL, no I/O.
 */
public final class BidCollectorBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(BidCollectorBehaviour.class);

    public BidCollectorBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        log.debug("{} action stub", getClass().getSimpleName());
        block();
    }
}