package it.unige.portcommand.lifecycle.events;

import it.unige.portcommand.util.Event;

/**
 * The game re-entered RUNNING after a pause (task 24). Published by
 * {@code GameLifecycle.resume()}. Consumers: {@code MainWindow} (hides the
 * pause overlay, flips menu enablement).
 */
public record GameResumedEvent() implements Event {
}
