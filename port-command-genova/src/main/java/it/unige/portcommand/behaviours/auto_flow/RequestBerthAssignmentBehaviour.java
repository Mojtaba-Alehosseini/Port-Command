package it.unige.portcommand.behaviours.auto_flow;

import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEFERRED in task 07 — kept a no-op stub. Berth assignment is requested via
 * {@link AnnounceArrivalBehaviour}'s REQUEST to the HarbourMaster; this separate
 * behaviour stays a stub until a distinct re-assignment flow is needed. Retained to
 * preserve the locked behaviour catalogue (51, ADR-01).
 */
public final class RequestBerthAssignmentBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(RequestBerthAssignmentBehaviour.class);

    public RequestBerthAssignmentBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        log.debug("{} action stub", getClass().getSimpleName());
    }
}