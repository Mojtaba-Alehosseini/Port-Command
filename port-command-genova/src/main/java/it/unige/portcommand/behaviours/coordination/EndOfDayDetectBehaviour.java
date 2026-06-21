package it.unige.portcommand.behaviours.coordination;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub behaviour (task 05). The owning agent task fills the plan body; until then
 * the body logs once and does nothing else. No blocking spin, no JPL, no I/O.
 */
public final class EndOfDayDetectBehaviour extends TickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayDetectBehaviour.class);

    public EndOfDayDetectBehaviour(Agent agent) {
        super(agent, 1000L);
    }

    @Override
    protected void onTick() {
        log.debug("{} tick stub", getClass().getSimpleName());
    }
}