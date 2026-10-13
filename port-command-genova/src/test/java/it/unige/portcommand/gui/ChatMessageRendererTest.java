package it.unige.portcommand.gui;

import java.util.List;

import it.unige.portcommand.gui.model.DialogueTabModel.ChatEntry;
import it.unige.portcommand.gui.model.DialogueTabModel.Speaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageRendererTest {

    @Test
    void playerEntryIsRightAligned() {
        String html = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "2000 for 5 hours"));
        assertTrue(html.contains("text-align:right"));
        assertTrue(html.contains("2000 for 5 hours"));
    }

    @Test
    void vesselEntryIsLeftAligned() {
        String html = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.VESSEL, "Opens at €1500"));
        assertTrue(html.contains("text-align:left"));
    }

    @Test
    void assistantEntryIsVisuallyDistinctFromPlayerAndVessel() {
        String assistant = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.ASSISTANT, "🤖 Assistant: take it"));
        String player = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "take it"));
        String vessel = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.VESSEL, "take it"));
        assertFalse(assistant.equals(player));
        assertFalse(assistant.equals(vessel));
        assertTrue(assistant.contains("🤖 Assistant: take it"), "the producing behaviour's own prefix must not be stripped or duplicated");
    }

    @Test
    void systemEntryIsCenteredAndDistinctFromConversationalBubbles() {
        String html = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.SYSTEM, "Deal closed."));
        assertTrue(html.contains("text-align:center"));
    }

    @Test
    void htmlSpecialCharactersAreEscaped() {
        String html = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "5 < 10 & 10 > 5"));
        assertFalse(html.contains("5 < 10"), "a literal '<' would corrupt the surrounding HTML");
        assertTrue(html.contains("&lt;") && html.contains("&amp;") && html.contains("&gt;"));
    }

    @Test
    void renderTranscriptWrapsEveryEntryInOneHtmlDocument() {
        String html = ChatMessageRenderer.renderTranscript(List.of(
                new ChatEntry(Speaker.VESSEL, "opening"),
                new ChatEntry(Speaker.PLAYER, "counter")));
        assertTrue(html.startsWith("<html>"));
        assertTrue(html.endsWith("</html>"));
        assertTrue(html.contains("opening"));
        assertTrue(html.contains("counter"));
    }

    // ==================== S15 (2026-10-12): the parse note after a player message ====================

    /** A player bubble exactly as it was rendered before S15 — the baseline an untagged entry must
     * still match byte for byte. */
    private static final String UNTAGGED_PLAYER_BUBBLE = "<div style=\"text-align:right;margin:4px\">"
            + "<span style=\"background:#cce5ff;padding:4px 8px;border-radius:8px\">2000 for 5 hours</span></div>";

    @Test
    void aTaggedPlayerEntryShowsItsTagAfterTheBubble() {
        String html = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "2000 for 5 hours", "(DCG)"));

        int bubbleEnd = html.indexOf("2000 for 5 hours</span>");
        int tag = html.indexOf("(DCG)");
        assertTrue(bubbleEnd >= 0 && tag > bubbleEnd, "the note comes after the bubble has closed: " + html);
        assertTrue(html.startsWith("<div style=\"text-align:right;margin:4px\">") && html.endsWith("</div>"),
                "and inside the same right-aligned line, so it stays with its own message: " + html);
        assertEquals(1, html.split("<div", -1).length - 1, "one line, not a second block: " + html);
        // Small and grey: a remark about the message, not part of what the player said.
        assertTrue(html.contains("</span> <span style=\"color:#888888;font-size:9px\">(DCG)</span></div>"), html);
    }

    @Test
    void aRasaTagIsRenderedWithItsConfidence() {
        String html = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "so?", "(Rasa 0.97)"));
        assertTrue(html.contains(">(Rasa 0.97)</span>"), html);
    }

    @Test
    void anUntaggedPlayerEntryRendersExactlyAsBefore() {
        assertEquals(UNTAGGED_PLAYER_BUBBLE,
                ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "2000 for 5 hours")));
        assertEquals(UNTAGGED_PLAYER_BUBBLE,
                ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "2000 for 5 hours", "")));
        assertEquals(UNTAGGED_PLAYER_BUBBLE,
                ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "2000 for 5 hours", null)),
                "a null tag is an empty one");
    }

    @Test
    void theTagIsEscaped() {
        String html = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "deal", "(<b>R&D</b>)"));
        assertFalse(html.contains("<b>"), "a tag must never be able to inject markup: " + html);
        assertTrue(html.contains("(&lt;b&gt;R&amp;D&lt;/b&gt;)"), html);
    }

    @Test
    void aTagOnANonPlayerEntryIsIgnored() {
        for (Speaker speaker : List.of(Speaker.VESSEL, Speaker.ASSISTANT, Speaker.SYSTEM)) {
            String tagged = ChatMessageRenderer.renderEntry(new ChatEntry(speaker, "Opens at €1500", "(DCG)"));
            String untagged = ChatMessageRenderer.renderEntry(new ChatEntry(speaker, "Opens at €1500"));
            assertEquals(untagged, tagged, speaker + " lines are never parsed, so they never show a note");
            assertFalse(tagged.contains("(DCG)"), tagged);
        }
    }

    @Test
    void aVesselBubbleRendersExactlyAsBefore() {
        // The vessel's bubble shares the player's markup builder; the note must not have leaked in.
        assertEquals("<div style=\"text-align:left;margin:4px\"><span style=\"background:#e0e0e0;"
                        + "padding:4px 8px;border-radius:8px\">Opens at €1500</span></div>",
                ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.VESSEL, "Opens at €1500")));
    }

    @Test
    void theTagTravelsThroughAWholeTranscript() {
        String html = ChatMessageRenderer.renderTranscript(List.of(
                new ChatEntry(Speaker.VESSEL, "Opens at €1500"),
                new ChatEntry(Speaker.PLAYER, "1900 for 6h", "(DCG)"),
                new ChatEntry(Speaker.SYSTEM, "→ Demanding €1900 for 6h."),
                new ChatEntry(Speaker.PLAYER, "so?")));
        assertEquals(1, html.split("\\(DCG\\)", -1).length - 1, "exactly one message is tagged: " + html);
        assertTrue(html.indexOf("1900 for 6h") < html.indexOf("(DCG)")
                && html.indexOf("(DCG)") < html.indexOf("Demanding"), "and the note sits with that message: " + html);
    }
}
