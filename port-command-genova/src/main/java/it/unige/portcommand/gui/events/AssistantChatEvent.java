package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * The Assistant's recommendation or explanation prose, for the GUI's ChatPanel (task 17/19).
 * See {@link HintButtonEvent}'s ownership note.
 *
 * <p><b>Hint replies are two-step (S12, live-playtest F-8).</b> The deterministic template is
 * published the moment Hint is pressed ({@link #hintTemplate}); if the LLM's paraphrase later
 * passes the hallucination validator it is published as a {@link #hintRevision} carrying the
 * SAME {@code hintId}, and the tab replaces that template in place. Whether a revision may land
 * (tab still open, still the newest hint) is decided GUI-side, in one EDT step, by
 * {@code DialogueTabModel}. Every other producer uses the two-argument constructor and appends.
 *
 * @param dialogueId the negotiation (or explain-request) this message pertains to
 * @param text       the shown text — the plain template, or LLM prose that passed the validator
 * @param hintId     {@link #NO_HINT} for an ordinary message; otherwise the id of one Hint press
 * @param revision   true iff {@code text} replaces the template already shown for {@code hintId}
 */
public record AssistantChatEvent(String dialogueId, String text, long hintId, boolean revision) implements Event {

    /** The {@code hintId} of every message that is not a Hint reply. */
    public static final long NO_HINT = 0L;

    public AssistantChatEvent {
        if (revision && hintId == NO_HINT) {
            throw new IllegalArgumentException("a revision must name the hint it revises");
        }
    }

    /** An ordinary Assistant message (autopilot narration, gate refusals, notices): appended. */
    public AssistantChatEvent(String dialogueId, String text) {
        this(dialogueId, text, NO_HINT, false);
    }

    /** The template shown at once for Hint press {@code hintId}. */
    public static AssistantChatEvent hintTemplate(String dialogueId, long hintId, String text) {
        return new AssistantChatEvent(dialogueId, text, hintId, false);
    }

    /** The validated LLM wording that replaces Hint press {@code hintId}'s template. */
    public static AssistantChatEvent hintRevision(String dialogueId, long hintId, String text) {
        return new AssistantChatEvent(dialogueId, text, hintId, true);
    }

    public boolean isHint() {
        return hintId != NO_HINT;
    }
}
