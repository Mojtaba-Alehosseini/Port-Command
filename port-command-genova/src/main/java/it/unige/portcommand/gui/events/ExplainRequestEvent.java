package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Fired by the GUI "Why?" button (task 19) to ask the Assistant to regenerate a past decision's
 * explanation. {@code ExplainEventBehaviour} (planning/10 §10.8b) subscribes and looks the
 * decision up in its small bounded recommendation cache. See {@link HintButtonEvent}'s
 * ownership note.
 *
 * @param decisionId the id of a previously-issued {@code Recommendation}
 */
public record ExplainRequestEvent(String decisionId) implements Event {
}
