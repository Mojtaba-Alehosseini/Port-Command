package it.unige.portcommand.behaviours.coordination;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.gui.events.EndOfDayEvent;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DETECT-ONLY. Ticks every sim-minute; on a midnight crossing publishes a bare
 * {@link EndOfDayEvent} for the day that just ended and stops there —
 * income/expense aggregation, wallet/reputation mutation, and autosave all belong
 * to {@code DayRolloverCoordinator} (task 24), which subscribes to this event
 * (INVARIANTS.md: EOD math belongs to DayRolloverCoordinator; this behaviour's ONLY
 * responsibility is detecting the crossing).
 *
 * <p>{@code SimClock.isMidnightCrossed()} flips {@code gameDay()} to the day just
 * STARTED before returning {@code true}, so the day that just ENDED is
 * {@code gameDay()-1} (never &lt;1 — the clock starts on day 1 and the earliest
 * possible crossing is into day 2).
 */
public final class EndOfDayDetectBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayDetectBehaviour.class);
    private static final long TICK_SIM_MILLIS = 60_000L; // 1 sim-minute

    public EndOfDayDetectBehaviour(Agent agent, SimClock simClock) {
        super(agent, simClock, TICK_SIM_MILLIS);
    }

    @Override
    protected void onSimTick() {
        if (!simClock().isMidnightCrossed()) {
            return;
        }
        int endedGameDay = simClock().gameDay() - 1;
        ((HarbourMasterAgent) myAgent).eventBus().publish(new EndOfDayEvent(endedGameDay));
        log.info("midnight crossed -> EndOfDayEvent(gameDay={})", endedGameDay);
    }
}
