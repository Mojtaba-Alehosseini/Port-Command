package it.unige.portcommand.gui.events;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.util.Event;

/**
 * Fired when the player presses the negotiation panel's "Hint" button (task 19). Carries the
 * dialogue's current observable snapshot directly, rather than a {@code dialogueId} the
 * Assistant must "look up" through some registry: HarbourMasterAgent (task 11), which owns
 * dialogue lifecycle, does not exist yet, and the panel firing this event already has every
 * observable field on screen to build the snapshot itself.
 *
 * <p><b>Ownership note (dated 2026-07-14):</b> {@code gui.events} is nominally task 17's
 * package (planning/17 §"Files to create" lists the other ~10 GUI event types), but task 17
 * runs after task 10 in every build ordering this project has used, and task 10's own planning
 * file writes {@code subscribe(HintButtonEvent.class, ...)} directly. Task 10 therefore declares
 * only the 7 event types it needs (this one plus {@link NegotiationOpenedEvent},
 * {@link PlayerCommandEvent}, {@link AssistantChatEvent}, {@link NotificationEvent},
 * {@link PolicyParsedEvent}, {@link ExplainRequestEvent}); task 17 creates the rest and may
 * adjust these seven's shape if the GUI needs differ once panels are built.
 *
 * @param dialogueId the negotiation's conversation id (correlates the reply back to the right chat tab)
 * @param snapshot   the dialogue's current observable state
 */
public record HintButtonEvent(String dialogueId, WalkInDialogueSnapshot snapshot) implements Event {
}
