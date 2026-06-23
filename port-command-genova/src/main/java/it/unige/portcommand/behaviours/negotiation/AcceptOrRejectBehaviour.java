package it.unige.portcommand.behaviours.negotiation;

import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FOLDED into {@link EvaluateCounterOfferBehaviour} (task-07 §64) — accept/reject is
 * dispatched there (ACCEPT_PROPOSAL on a deal, PROPOSE on a counter). Retained as a
 * no-op stub to preserve the locked behaviour catalogue (51, ADR-01).
 */
public final class AcceptOrRejectBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(AcceptOrRejectBehaviour.class);

    public AcceptOrRejectBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        log.debug("{} action stub", getClass().getSimpleName());
    }
}