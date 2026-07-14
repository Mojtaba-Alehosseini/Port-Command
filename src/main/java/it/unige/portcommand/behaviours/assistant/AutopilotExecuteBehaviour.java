package it.unige.portcommand.behaviours.assistant;

import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub behaviour (task 05). The owning agent task fills the plan body; until then
 * the body logs once and does nothing else. No blocking spin, no JPL, no I/O.
 */
public final class AutopilotExecuteBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(AutopilotExecuteBehaviour.class);

    public AutopilotExecuteBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        log.debug("{} action stub", getClass().getSimpleName());
    }
}