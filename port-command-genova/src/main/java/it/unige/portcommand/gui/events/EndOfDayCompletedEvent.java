package it.unige.portcommand.gui.events;

import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.util.Event;

/**
 * The full EOD settlement summary computed by {@code DayRolloverCoordinator}
 * (task 24) — NOT the bare {@link EndOfDayEvent} detect signal (task 11),
 * which carries only the game-day counter and no figures. Task 20's
 * {@code EndOfDaySummaryDialog} subscribes and displays; task 22's autosave
 * subscribes too. See {@code planning/24_game_loop_integration.md}'s own
 * publish-order note: this must fire BEFORE {@code DayRolloverEvent} so the
 * dialog can read the previous day's stats.
 *
 * @param summary the computed settlement
 */
public record EndOfDayCompletedEvent(EndOfDaySummary summary) implements Event {
}
