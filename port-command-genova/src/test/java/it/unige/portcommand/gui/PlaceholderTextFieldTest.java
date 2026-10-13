package it.unige.portcommand.gui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the chat input's grey prompt. What matters is what it is NOT — it is never the
 * field's text (so Send on a field that only shows an example sends nothing), it never shows where
 * typing would not work (a closed tab's non-editable field), and it does not change the field's
 * size (so it cannot widen the chat column).
 *
 * <p>Headless: a bare field, never put in a window. Every test body runs on the EDT; the paint
 * test draws the peerless field into an image, which needs no display.
 */
class PlaceholderTextFieldTest {

    private static final String EXAMPLE = "e.g. 1900 for 6h";

    /** Runs {@code body} on the EDT and reports a failed assertion as itself, not wrapped. */
    private static void onEdt(Runnable body) throws Exception {
        try {
            SwingUtilities.invokeAndWait(body);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    private static PlaceholderTextField fieldShowing(String placeholder) {
        PlaceholderTextField field = new PlaceholderTextField();
        field.setPlaceholder(placeholder);
        return field;
    }

    @Test
    @Timeout(5)
    void anEmptyEditableFieldShowsIt() throws Exception {
        onEdt(() -> {
            PlaceholderTextField field = fieldShowing(EXAMPLE);

            assertEquals(EXAMPLE, field.placeholder());
            assertTrue(field.showsPlaceholder());
        });
    }

    @Test
    @Timeout(5)
    void aFieldWithNoPlaceholderShowsNone() throws Exception {
        onEdt(() -> {
            PlaceholderTextField field = new PlaceholderTextField();
            assertEquals("", field.placeholder());
            assertFalse(field.showsPlaceholder());

            field.setPlaceholder(EXAMPLE);
            field.setPlaceholder(null);
            assertEquals("", field.placeholder(), "null means none");
            assertFalse(field.showsPlaceholder());

            field.setPlaceholder(EXAMPLE);
            field.setPlaceholder("");
            assertFalse(field.showsPlaceholder());
        });
    }

    @Test
    @Timeout(5)
    void anyTextHidesItAndAnEmptyFieldShowsItAgain() throws Exception {
        onEdt(() -> {
            PlaceholderTextField field = fieldShowing(EXAMPLE);

            field.setText("2");
            assertFalse(field.showsPlaceholder(), "the first character replaces the prompt");
            field.setText(" ");
            assertFalse(field.showsPlaceholder(), "a space is text too: the prompt must not sit under a caret that moved");

            field.setText("");
            assertTrue(field.showsPlaceholder(), "an emptied field prompts again");
            assertEquals(EXAMPLE, field.placeholder());
        });
    }

    @Test
    @Timeout(5)
    void getTextNeverReturnsIt() throws Exception {
        onEdt(() -> {
            PlaceholderTextField field = fieldShowing(EXAMPLE);

            assertTrue(field.showsPlaceholder(), "setup: the prompt is on screen");
            assertEquals("", field.getText(), "the prompt is paint, not text");
            assertEquals(0, field.getDocument().getLength());
            field.selectAll();
            assertNull(field.getSelectedText(), "there is nothing to select or copy");

            field.setText("deal");
            field.setPlaceholder("e.g. 200 more");
            assertEquals("deal", field.getText(), "changing the prompt never touches what the player typed");
        });
    }

    @Test
    @Timeout(5)
    void aNonEditableFieldShowsNone() throws Exception {
        onEdt(() -> {
            PlaceholderTextField field = fieldShowing(EXAMPLE);

            field.setEditable(false); // a closed tab's field: focusable, swallows keystrokes (#9)
            assertFalse(field.showsPlaceholder(), "a field that ignores typing must not invite it");
            assertEquals(EXAMPLE, field.placeholder(), "the prompt is kept, just not shown");

            field.setEditable(true);
            assertTrue(field.showsPlaceholder());
        });
    }

    @Test
    @Timeout(5)
    void aDisabledFieldShowsNone() throws Exception {
        onEdt(() -> {
            PlaceholderTextField field = fieldShowing(EXAMPLE);

            field.setEnabled(false);
            assertFalse(field.showsPlaceholder());

            field.setEnabled(true);
            assertTrue(field.showsPlaceholder());
        });
    }

    @Test
    @Timeout(5)
    void thePromptDoesNotChangeTheFieldsSize() throws Exception {
        // The chat column's minimum width depends on its widest row; a prompt that counted
        // towards the field's size would let a long example widen every tab.
        onEdt(() -> {
            PlaceholderTextField plain = new PlaceholderTextField();
            PlaceholderTextField prompted = fieldShowing("e.g. " + "split the difference ".repeat(20));

            assertEquals(plain.getMinimumSize(), prompted.getMinimumSize());
            assertEquals(plain.getPreferredSize(), prompted.getPreferredSize());
        });
    }

    /** How many of the field's own repaint listeners {@code document} carries. */
    private static long ownListeners(Document document) {
        return Arrays.stream(((AbstractDocument) document).getDocumentListeners())
                .filter(listener -> listener.getClass().getName().startsWith(PlaceholderTextField.class.getName()))
                .count();
    }

    @Test
    @Timeout(5)
    void aReplacedDocumentIsFollowed() throws Exception {
        onEdt(() -> {
            PlaceholderTextField field = fieldShowing(EXAMPLE);
            Document first = field.getDocument();
            assertEquals(1, ownListeners(first), "setup: the field repaints on every edit of its document");

            Document second = new PlainDocument();
            field.setDocument(second);

            assertEquals(0, ownListeners(first), "the old document is let go");
            assertEquals(1, ownListeners(second), "and the new one is followed");
            assertTrue(field.showsPlaceholder(), "the new document is empty");
            field.setText("deal");
            assertFalse(field.showsPlaceholder());
        });
    }

    private static BufferedImage paint(PlaceholderTextField field) {
        BufferedImage image = new BufferedImage(field.getWidth(), field.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            field.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static int differingPixels(BufferedImage a, BufferedImage b) {
        int differing = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    differing++;
                }
            }
        }
        return differing;
    }

    @Test
    @Timeout(10)
    void paintingDiffersOnlyWhileEmpty() throws Exception {
        // Two fields that differ in nothing but the prompt, drawn into images: the prompt must be
        // the ONLY difference, and only while it is meant to show.
        onEdt(() -> {
            PlaceholderTextField plain = new PlaceholderTextField();
            PlaceholderTextField prompted = fieldShowing(EXAMPLE);
            plain.setSize(240, 28);
            prompted.setSize(240, 28);

            assertTrue(differingPixels(paint(plain), paint(prompted)) > 0, "an empty field draws its prompt");

            plain.setText("1900 for 6h");
            prompted.setText("1900 for 6h");
            assertEquals(0, differingPixels(paint(plain), paint(prompted)),
                    "with text in it the field looks exactly like one that has no prompt");

            plain.setText("");
            prompted.setText("");
            assertTrue(differingPixels(paint(plain), paint(prompted)) > 0, "emptied, it draws the prompt again");

            plain.setEditable(false);
            prompted.setEditable(false);
            assertEquals(0, differingPixels(paint(plain), paint(prompted)), "a non-editable field draws none");
        });
    }

    @Test
    @Timeout(10)
    void aFieldTooSmallToDrawInPaintsWithoutFailing() throws Exception {
        // A column dragged shut can leave the field a sliver; painting it must not throw.
        onEdt(() -> {
            PlaceholderTextField field = fieldShowing(EXAMPLE);
            field.setSize(3, 3);

            BufferedImage image = paint(field);

            assertEquals(3, image.getWidth());
            assertTrue(field.showsPlaceholder(), "it still counts as showing; there is simply no room to draw it");
        });
    }
}
