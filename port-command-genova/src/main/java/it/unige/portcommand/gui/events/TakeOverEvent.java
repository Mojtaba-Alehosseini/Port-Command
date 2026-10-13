package it.unige.portcommand.gui.events;

import java.util.Objects;

import it.unige.portcommand.util.Event;

/**
 * The player pressed "Take over" on a tab the Assistant was negotiating in (S17a, ADR-21,
 * decision A2). The {@code AssistantAgent} drops any move it was about to make there, never
 * makes another in that negotiation, and answers with an {@link AutopilotOwnershipEvent} that
 * lists the dialogue - which is what unlocks the tab's input. The tab does not unlock itself: the
 * Assistant's answer is the one source of who owns a tab.
 *
 * @param dialogueId the negotiation being taken over
 */
public record TakeOverEvent(String dialogueId) implements Event {

    public TakeOverEvent {
        Objects.requireNonNull(dialogueId, "dialogueId");
    }
}
