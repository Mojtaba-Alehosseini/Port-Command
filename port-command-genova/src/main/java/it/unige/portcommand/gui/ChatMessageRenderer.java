package it.unige.portcommand.gui;

import java.util.List;

import it.unige.portcommand.gui.model.DialogueTabModel.ChatEntry;

/**
 * Pure HTML-snippet rendering for one dialogue tab's transcript — no Swing import,
 * so its output is directly assertable in a headless test. {@code DialogueTabView}
 * feeds the result straight into a {@code JTextPane} via {@code setContentType(
 * "text/html")} + {@code setText(...)}. Player messages right-aligned (light blue),
 * vessel messages left-aligned (light grey), Assistant messages full-width and
 * visually distinct (yellow) — the "🤖 Assistant" / "🤖 Assistant (autopilot)" prefix
 * is already baked into the text by the producing behaviour, this class never adds
 * its own. System notices (deal closed, withdrawal, unsupported command, parse
 * error) share one centered, muted style — they're all "the system talking", not a
 * fourth conversational party.
 *
 * <p>S15 (2026-10-12): a player message may carry a small grey note after its bubble saying which
 * stage of the NLP pipeline read it — {@code (DCG)} or {@code (Rasa 0.97)} (the text comes from
 * {@code ParseTag}; this class only draws it). The note sits inside the same right-aligned line as
 * the bubble, so it stays with its own message however the transcript wraps. An entry with no tag
 * renders exactly as it did before S15, byte for byte; a tag on a vessel's or a system line is not
 * drawn (only a player's message is ever parsed).
 *
 * <p>S17a (ADR-21, decision A3): an Assistant entry may carry a details line — the numbers and the
 * checks behind a Hint ({@code EV €640 · chance 62% · R7 ✓}), which the hint's own sentence now
 * leaves out. It travels in the same {@code tag} field and is drawn in the same small grey, but
 * UNDER the text and inside the Assistant's block: it belongs to that advice, and when the model's
 * wording later replaces the sentence above it, it stays where it is. An Assistant entry without
 * one renders exactly as it did before S17a, byte for byte.
 */
public final class ChatMessageRenderer {

    /** The note's style: smaller than the 11px body text and mid grey, so it reads as a remark
     * about the message and not as part of what the player said. S17a: the details line under a
     * Hint is drawn in it too — the same kind of remark, about the Assistant's sentence. */
    private static final String TAG_STYLE = "color:#888888;font-size:9px";

    private ChatMessageRenderer() {
    }

    public static String renderTranscript(List<ChatEntry> entries) {
        StringBuilder html = new StringBuilder("<html><body style=\"font-family:sans-serif;font-size:11px\">");
        for (ChatEntry entry : entries) {
            html.append(renderEntry(entry));
        }
        html.append("</body></html>");
        return html.toString();
    }

    static String renderEntry(ChatEntry entry) {
        String text = escape(entry.text());
        return switch (entry.speaker()) {
            case PLAYER -> bubble("right", "#cce5ff", text, tagNote(entry.tag()));
            case VESSEL -> bubble("left", "#e0e0e0", text, "");
            case ASSISTANT -> "<div style=\"margin:4px\"><span style=\"background:#fff3b0;padding:4px 8px;"
                    + "border-radius:8px;display:block\">" + text + detailsLine(entry.tag()) + "</span></div>";
            case SYSTEM -> "<div style=\"text-align:center;margin:4px;color:#883333;font-style:italic\">"
                    + text + "</div>";
        };
    }

    /** @param after ready-made HTML placed after the bubble, inside the same line; empty for none */
    private static String bubble(String align, String background, String text, String after) {
        return "<div style=\"text-align:" + align + ";margin:4px\"><span style=\"background:" + background
                + ";padding:4px 8px;border-radius:8px\">" + text + "</span>" + after + "</div>";
    }

    /** S15: the grey note for a player message, or the empty string when there is no tag — which
     * is what keeps an untagged message's HTML identical to the pre-S15 output. The tag is escaped
     * like any other text: today it only ever holds {@code ParseTag}'s fixed forms, but nothing
     * that reaches the transcript is trusted to be HTML-safe. */
    private static String tagNote(String tag) {
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        return " <span style=\"" + TAG_STYLE + "\">" + escape(tag) + "</span>";
    }

    /** S17a (ADR-21): the details line under an Assistant entry's text — a line break, then the
     * details in the note's small grey, still inside the Assistant's own block. The empty string
     * when there are none, which is what keeps an ordinary Assistant message's HTML identical to
     * the pre-S17a output. Escaped like any other text: the line is built by the Assistant from
     * figures and rule ids, but nothing that reaches the transcript is trusted to be HTML-safe. */
    private static String detailsLine(String details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        return "<br><span style=\"" + TAG_STYLE + "\">" + escape(details) + "</span>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
