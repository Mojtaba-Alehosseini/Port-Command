package it.unige.portcommand.scenario.events;

import it.unige.portcommand.util.Event;

/**
 * Bus notification that the tutorial advanced to {@code step} (1-based, of
 * {@link TutorialStepAdvanceEvent#TOTAL_STEPS}). Consumer: task 21's
 * {@code TutorialOverlay} (until it exists, the scripted event also posts a
 * placeholder {@code NotificationEvent} banner — see
 * {@link TutorialStepAdvanceEvent}).
 */
public record TutorialStepAdvancedEvent(int step, String text) implements Event {
}
