package it.unige.portcommand.behaviours.auto_flow;

import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEFERRED in task 07 — kept a no-op stub. The tug-escort REQUEST / CFP-trigger flow
 * is owned by the HarbourMaster/CNP path (tasks 08/11); the vessel does not initiate
 * the CFP. Retained as a stub to preserve the locked behaviour catalogue (51, ADR-01).
 */
public final class RequestTugEscortBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(RequestTugEscortBehaviour.class);

    public RequestTugEscortBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        log.debug("{} action stub", getClass().getSimpleName());
    }
}