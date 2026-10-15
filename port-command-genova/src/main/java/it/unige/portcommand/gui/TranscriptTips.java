package it.unige.portcommand.gui;

import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;

/**
 * S19 (playtest 5, PT25 / PT33 / PT42): the tooltip a chat transcript shows at a position - the
 * {@code title} of the span that position sits in. A Hint's details line carries one: what its
 * parts mean, and what the cost is made of ({@code ChatMessageRenderer}). Every other text has
 * none, and gets no tooltip.
 */
final class TranscriptTips {

    private TranscriptTips() {
    }

    /** The title of the span at {@code position}, or {@code null} when there is none. */
    static String titleAt(HTMLDocument document, int position) {
        if (document == null || position < 0 || position > document.getLength()) {
            return null;
        }
        Element element = document.getCharacterElement(position);
        // An inline span keeps its own attributes under its tag; the innermost one wins, so the
        // details line's span - not the Hint's outer block - answers for the text inside it.
        Object span = element.getAttributes().getAttribute(HTML.Tag.SPAN);
        if (span instanceof AttributeSet attributes) {
            Object title = attributes.getAttribute(HTML.Attribute.TITLE);
            return title == null ? null : title.toString();
        }
        return null;
    }

    /** {@code text} as a tooltip: its lines kept as lines. */
    static String asTooltip(String text) {
        return "<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>") + "</html>";
    }
}
