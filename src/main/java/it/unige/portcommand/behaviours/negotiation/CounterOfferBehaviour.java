package it.unige.portcommand.behaviours.negotiation;

import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FOLDED into {@link EvaluateCounterOfferBehaviour} (task-07 §64) — a counter-offer is
 * emitted there as a fresh PROPOSE. Retained as a no-op stub to preserve the locked
 * behaviour catalogue (51, ADR-01).
 */
public final class CounterOfferBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(CounterOfferBehaviour.class);

    public CounterOfferBehaviour(Agent agent) {
        super(agent);
    }

    @Override
    public void action() {
        log.debug("{} action stub", getClass().getSimpleName());
    }
}