package it.unige.portcommand.gui;

import java.util.List;

import it.unige.portcommand.gui.model.DialogueTabModel.ChatEntry;
import it.unige.portcommand.gui.model.DialogueTabModel.Speaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageRendererTest {

    /** S19 (playtest 5, PT25): the Hint's short line never changes - the model's explanation is
     * added under it as "Why: ...". And its details line carries its tooltip (PT33 / PT42). */
    @Test
    void aHintsWhyGoesUnderItsLineAndItsDetailsCarryTheirTooltip() {
        String html = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.ASSISTANT,
                "Counter at €7200 for 6h.", "Their offer €6,000 · fits berth ✓ (R7)",
                "Asking a bit more is safe: she can still counter.", "Your cost ≈ €1,100 = ..."));

        assertTrue(html.contains("Counter at €7200 for 6h.<br><i>Why: Asking a bit more is safe: she can still counter.</i>"),
                html);
        assertTrue(html.contains("title=\"Your cost ≈ €1,100 = ...\""), html);
        assertTrue(html.indexOf("Why:") < html.indexOf("Their offer"), "the details line stays at the bottom: " + html);

        String plain = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.ASSISTANT, "Counter at €7200 for 6h.",
                "Their offer €6,000 · fits berth ✓ (R7)"));
        assertFalse(plain.contains("Why:"), "no explanation yet, no Why line");
        assertFalse(plain.contains("title="), "no tooltip, no title");
    }

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
    void aTagOnAVesselOrSystemEntryIsIgnored() {
        // S17a (ADR-21): the Assistant left this list — its entry's tag is the details line of a
        // Hint, and it IS drawn (the tests at the end of this class). A vessel's and a system
        // line still have nothing to carry: nobody parses them and nobody advises on them.
        for (Speaker speaker : List.of(Speaker.VESSEL, Speaker.SYSTEM)) {
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

    // ==================== S17a (ADR-21): the details line under a Hint ====================

    private static final String HINT_TEXT = "Counter at €1580 for 6h.";
    private static final String HINT_DETAILS = "EV €640 · chance 62% · R7 ✓";

    /** An Assistant block exactly as it was rendered before S17a — the baseline an entry without
     * details must still match byte for byte. */
    private static final String ASSISTANT_BLOCK_WITHOUT_DETAILS = "<div style=\"margin:4px\">"
            + "<span style=\"background:#fff3b0;padding:4px 8px;border-radius:8px;display:block\">"
            + HINT_TEXT + "</span></div>";

    @Test
    void anAssistantEntryWithDetailsShowsThemUnderItsTextInTheNotesGreyStyle() {
        String html = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.ASSISTANT, HINT_TEXT, HINT_DETAILS));

        // The text, a line break, the details in the parse note's own small grey — and only then
        // does the Assistant's yellow block close: the details belong to that advice.
        assertEquals("<div style=\"margin:4px\">"
                + "<span style=\"background:#fff3b0;padding:4px 8px;border-radius:8px;display:block\">"
                + HINT_TEXT + "<br><span style=\"color:#888888;font-size:9px\">" + HINT_DETAILS + "</span>"
                + "</span></div>", html);
        assertEquals(1, html.split("<div", -1).length - 1, "one block, not a second one under it: " + html);
        // The very style a player's parse note is drawn in, not a look-alike.
        String parseNote = ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.PLAYER, "deal", "(DCG)"));
        assertTrue(parseNote.contains("<span style=\"color:#888888;font-size:9px\">(DCG)</span>"), parseNote);
    }

    @Test
    void anAssistantEntryWithoutDetailsRendersExactlyAsBefore() {
        assertEquals(ASSISTANT_BLOCK_WITHOUT_DETAILS,
                ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.ASSISTANT, HINT_TEXT)));
        assertEquals(ASSISTANT_BLOCK_WITHOUT_DETAILS,
                ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.ASSISTANT, HINT_TEXT, "")));
        assertEquals(ASSISTANT_BLOCK_WITHOUT_DETAILS,
                ChatMessageRenderer.renderEntry(new ChatEntry(Speaker.ASSISTANT, HINT_TEXT, null)),
                "null details are no details");
    }

    @Test
    void theDetailsAreEscaped() {
        String html = ChatMessageRenderer.renderEntry(
                new ChatEntry(Speaker.ASSISTANT, HINT_TEXT, "wind <b>32</b> kn & rising [R15]"));
        assertFalse(html.contains("<b>"), "details must never be able to inject markup: " + html);
        assertTrue(html.contains(">wind &lt;b&gt;32&lt;/b&gt; kn &amp; rising [R15]</span>"), html);
    }

    @Test
    void theDetailsTravelThroughAWholeTranscriptWithTheirOwnHint() {
        String html = ChatMessageRenderer.renderTranscript(List.of(
                new ChatEntry(Speaker.VESSEL, "Opens at €1500"),
                new ChatEntry(Speaker.ASSISTANT, HINT_TEXT, HINT_DETAILS),
                new ChatEntry(Speaker.ASSISTANT, "🤖 Assistant (autopilot): countered at €1580."),
                new ChatEntry(Speaker.SYSTEM, "→ Demanding €1580 for 6h.")));
        assertEquals(1, html.split("chance 62%", -1).length - 1, "exactly one entry has details: " + html);
        assertTrue(html.indexOf(HINT_TEXT) < html.indexOf(HINT_DETAILS)
                && html.indexOf(HINT_DETAILS) < html.indexOf("autopilot"), "and they sit under their own hint: " + html);
    }
}
