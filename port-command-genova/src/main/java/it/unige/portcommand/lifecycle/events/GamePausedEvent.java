package it.unige.portcommand.lifecycle.events;

import it.unige.portcommand.util.Event;

/**
 * The game entered PAUSED (task 24). Published by {@code GameLifecycle.pause()}.
 * Consumers: {@code MainWindow} (shows the pause overlay, flips menu enablement).
 * Agent-side nothing subscribes — behaviours gate on {@code SimClock.isPaused()},
 * which {@code pause()} sets before this event is published.
 */
public record GamePausedEvent() implements Event {
}
