package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * The Assistant's recommendation or explanation prose, for the GUI's ChatPanel (task 17/19).
 * See {@link HintButtonEvent}'s ownership note.
 *
 * @param dialogueId the negotiation (or explain-request) this message pertains to
 * @param text       the shown text — either LLM-polished prose (validated) or the plain template
 */
public record AssistantChatEvent(String dialogueId, String text) implements Event {
}
