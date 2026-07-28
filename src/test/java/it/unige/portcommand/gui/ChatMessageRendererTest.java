package it.unige.portcommand.gui;

import java.util.List;

import it.unige.portcommand.gui.model.DialogueTabModel.ChatEntry;
import it.unige.portcommand.gui.model.DialogueTabModel.Speaker;
import org.junit.jupiter.api.Test;

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
}
