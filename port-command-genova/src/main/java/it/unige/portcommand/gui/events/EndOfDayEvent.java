package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Bare midnight-crossing detection, published by {@code EndOfDayDetectBehaviour}
 * (HarbourMaster, task 11). Carries ONLY the sim game-day counter (matches
 * {@code SimClock.gameDay()} — this codebase has no calendar-date epoch) — no
 * income/expense/reputation math. {@code DayRolloverCoordinator} (task 24)
 * subscribes and does the heavy lifting (INVARIANTS.md: EOD math belongs to it).
 */
public record EndOfDayEvent(int gameDay) implements Event {

    public EndOfDayEvent {
        if (gameDay < 1) {
            throw new IllegalArgumentException("gameDay must be >= 1, got " + gameDay);
        }
    }
}
