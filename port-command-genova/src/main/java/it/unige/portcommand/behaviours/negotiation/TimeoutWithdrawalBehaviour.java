package it.unige.portcommand.behaviours.negotiation;

import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub behaviour (task 05). The owning agent task fills the plan body; until then
 * the body logs once and does nothing else. No blocking spin, no JPL, no I/O.
 */
public final class TimeoutWithdrawalBehaviour extends WakerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(TimeoutWithdrawalBehaviour.class);

    public TimeoutWithdrawalBehaviour(Agent agent) {
        super(agent, 1000L);
    }

    @Override
    protected void onWake() {
        log.debug("{} wake stub", getClass().getSimpleName());
    }
}