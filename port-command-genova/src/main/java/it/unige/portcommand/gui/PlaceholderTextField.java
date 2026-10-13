package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;

/**
 * A text field that shows a grey prompt while it is empty (S15, 2026-10-12) — the chat tab's
 * input, where the prompt is a worked example of something the player can type
 * ({@code e.g. 1900 for 6h}, from {@code InputExamples}).
 *
 * <p><b>The prompt is paint, never text.</b> It is drawn over the empty field and the document is
 * not touched: {@link #getText()} stays {@code ""}, so pressing Send on a field that only shows an
 * example sends nothing, and the first key the player types replaces nothing. That is the whole
 * reason this is a component and not a pre-filled field: the pre-S15 "Make an offer" button
 * pre-filled the input with {@code how about }, which the player then had to keep or delete.
 *
 * <p><b>Shown only where typing would work</b> — the field must be empty, editable and enabled. A
 * closed tab's field is deliberately non-editable (it swallows keystrokes, #9); a prompt there
 * would invite typing into a field that ignores it. It does show while the field has the focus:
 * the chat column focuses the input as soon as a tab is selected, so a prompt that hid on focus
 * would never be seen.
 *
 * <p>Swing has no placeholder support and the project sets no look-and-feel, so this paints the
 * text itself, on the field's own baseline and in the field's own font.
 */
final class PlaceholderTextField extends JTextField {

    /** Mid grey: clearly not the player's own (black) text, still readable on the white field. */
    static final Color PLACEHOLDER_COLOUR = new Color(0x99, 0x99, 0x99);

    /** The field's own repaint after an edit covers the edited text; the prompt may reach further
     * than that, and it has to go (or come back) on the very keystroke that fills (or empties)
     * the field — so every edit repaints the whole field. */
    private final DocumentListener repaintOnEdit = new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent e) {
            repaint();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            repaint();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            repaint();
        }
    };

    private String placeholder = "";

    PlaceholderTextField() {
        getDocument().addDocumentListener(repaintOnEdit);
        // A replaced document must be followed, or the prompt would stop tracking the text. Done
        // through the "document" property rather than by overriding setDocument, which the
        // superclass constructor calls before this class's fields exist.
        addPropertyChangeListener("document", event -> {
            if (event.getOldValue() instanceof Document old) {
                old.removeDocumentListener(repaintOnEdit);
            }
            if (event.getNewValue() instanceof Document current) {
                current.addDocumentListener(repaintOnEdit);
            }
            repaint();
        });
    }

    /** Sets the prompt; {@code null} or empty means none. Never changes the field's text. */
    void setPlaceholder(String text) {
        String next = text == null ? "" : text;
        if (!next.equals(placeholder)) {
            placeholder = next;
            repaint();
        }
    }

    /** The prompt as set (empty for none) — whether or not it is being shown right now. */
    String placeholder() {
        return placeholder;
    }

    /** Whether the prompt is on screen: there is one, and the field is empty, editable and enabled. */
    boolean showsPlaceholder() {
        return !placeholder.isEmpty() && isEditable() && isEnabled() && getDocument().getLength() == 0;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!showsPlaceholder()) {
            return;
        }
        Insets insets = getInsets();
        int width = getWidth() - insets.left - insets.right;
        int height = getHeight() - insets.top - insets.bottom;
        if (width <= 0 || height <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            // The same text smoothing the field's own text gets (Swing keeps the look-and-feel's
            // choice under these two keys), so the prompt does not look like a different font.
            Object smoothing = getClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING);
            if (smoothing != null && RenderingHints.KEY_TEXT_ANTIALIASING.isCompatibleValue(smoothing)) {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, smoothing);
                Object contrast = getClientProperty(RenderingHints.KEY_TEXT_LCD_CONTRAST);
                if (contrast != null && RenderingHints.KEY_TEXT_LCD_CONTRAST.isCompatibleValue(contrast)) {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, contrast);
                }
            }
            // Inside the border and margin only: a prompt longer than the field is cut at the
            // edge the field's own text would be cut at.
            g2.clipRect(insets.left, insets.top, width, height);
            g2.setFont(getFont());
            g2.setColor(PLACEHOLDER_COLOUR);
            FontMetrics metrics = g2.getFontMetrics();
            int baseline = getBaseline(getWidth(), getHeight());
            if (baseline < 0) {
                // The look-and-feel reports no baseline: centre the line by hand.
                baseline = insets.top + Math.max(0, (height - metrics.getHeight()) / 2) + metrics.getAscent();
            }
            g2.drawString(placeholder, insets.left, baseline);
        } finally {
            g2.dispose();
        }
    }
}
