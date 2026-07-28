package it.unige.portcommand.lifecycle.events;

import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.util.Event;

/**
 * The day counter advanced (task 24). Published by {@code DayRolloverCoordinator}
 * as the LAST event of the end-of-day sequence — always AFTER
 * {@code EndOfDayCompletedEvent} (whose consumers read the ended day's stats)
 * and after {@code AutosaveRequestedEvent}. Consumers: {@code GameOverGuard}
 * (day-cap check); the HUD needs no subscription (the day label follows
 * {@code SimClockTickEvent}).
 *
 * @param newDay  the 1-based day now underway (the sim clock crossed into it
 *                before the EOD sequence ran)
 * @param summary the settled summary of the day that just ended
 */
public record DayRolloverEvent(int newDay, EndOfDaySummary summary) implements Event {
}
