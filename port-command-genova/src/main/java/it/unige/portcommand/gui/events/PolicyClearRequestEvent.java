package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * The player pressed "Clear policies" in Settings (S18a, ADR-23, decision N11): the Assistant
 * should forget every autopilot policy. {@code PolicyParseBehaviour} empties the registry on its
 * own thread and answers with a {@link PolicyListEvent} — the form shows the list as empty only
 * once the Assistant says it is.
 */
public record PolicyClearRequestEvent() implements Event {
}
