package it.unige.portcommand.gui.events;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.util.Event;

/**
 * Fired when a walk-in dialogue opens (round 1) or advances (a new counter round) — planning/17
 * §Step 17.2's sketch folds "opened" and "counter" into one event type rather than a separate
 * {@code WalkInOpenedEvent}. Task 10's {@code AutopilotExecuteBehaviour} reacts to every firing
 * while autopilot is enabled.
 *
 * <p>See {@link HintButtonEvent}'s ownership note — declared here (not task 17) because task 10
 * needs it first.
 *
 * @param dialogueId the negotiation's conversation id
 * @param snapshot   the dialogue's current observable state
 */
public record NegotiationOpenedEvent(String dialogueId, WalkInDialogueSnapshot snapshot) implements Event {
}
