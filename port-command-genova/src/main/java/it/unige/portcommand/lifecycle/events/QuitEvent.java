package it.unige.portcommand.lifecycle.events;

import it.unige.portcommand.util.Event;

/**
 * The player asked to quit (task 24). Published by {@code GameLifecycle.quit()}
 * (menu Game → Quit, after the confirmation dialog). Sole consumer:
 * {@code GameOverGuard}, which converts it to {@code GameOverEvent("quit")} —
 * the guard stays the single {@code GameOverEvent} emitter so quit cannot
 * double-fire against bankruptcy/day-cap.
 */
public record QuitEvent() implements Event {
}
