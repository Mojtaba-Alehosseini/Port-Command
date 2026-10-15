package it.unige.portcommand.gui;

import java.io.StringReader;
import java.util.List;

import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import it.unige.portcommand.gui.model.DialogueTabModel.ChatEntry;
import it.unige.portcommand.gui.model.DialogueTabModel.Speaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S19 (playtest 5, PT25 / PT33 / PT42): the Hint's details line explains itself on hover. The
 * transcript is the renderer's HTML read into a real {@code HTMLDocument} - no window needed.
 */
class TranscriptTipsTest {

    private static final String TIP = "Your cost ≈ €1,100 = 2 tugs × €350 + customs €100 + 6h at the berth × €50.\n"
            + "Fits berth (R7): the Prolog berth rule says the ship fits.";

    @Test
    void theDetailsLineShowsItsTooltipAndNothingElseDoes() throws Exception {
        String html = ChatMessageRenderer.renderTranscript(List.of(
                new ChatEntry(Speaker.VESSEL, "They offer €6,000 for 6h at berth 1."),
                new ChatEntry(Speaker.ASSISTANT, "Accept their offer of €6000 for 6h.",
                        "Their offer €6,000 · your cost ≈ €1,100", "", TIP)));
        HTMLEditorKit kit = new HTMLEditorKit();
        HTMLDocument document = (HTMLDocument) kit.createDefaultDocument();
        kit.read(new StringReader(html), document, 0);
        String text = document.getText(0, document.getLength());

        int details = text.indexOf("your cost");
        assertTrue(details > 0, text);
        assertEquals(TIP, TranscriptTips.titleAt(document, details), "over the details line");
        assertNull(TranscriptTips.titleAt(document, text.indexOf("Accept their offer")), "the hint's own line");
        assertNull(TranscriptTips.titleAt(document, text.indexOf("They offer")), "the vessel's line");
        assertNull(TranscriptTips.titleAt(document, -1), "outside the text");
    }
}
