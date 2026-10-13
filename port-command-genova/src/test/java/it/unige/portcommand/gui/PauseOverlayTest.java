package it.unige.portcommand.gui;

import java.awt.Rectangle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit C-04 (2026-07-27) — the PAUSED glass pane made the whole menu bar dead.
 *
 * <p>{@code JRootPane.RootLayout} sizes the glass pane to the ENTIRE root pane, menu-bar strip
 * included, and {@code JRootPane.addImpl} forces it to child index 0 — the top of the Swing
 * z-order. {@code PauseOverlay}'s empty {@code MouseAdapter} then makes it the event target for
 * every click in the window, so while paused nothing on the {@code JMenuBar} responded: Game,
 * Debug, Help, and — the contradiction that made this a defect rather than a design choice —
 * <b>Save</b> and <b>Load</b>, which {@code MainWindow.reflectMode()} deliberately keeps ENABLED
 * in PAUSED ({@code live = RUNNING || PAUSED}). Pause is the first thing a grader tries.
 *
 * <p>The test drives the same seam production uses: {@code MainWindow} supplies the live menu-bar
 * bounds converted into glass-pane coordinates, and this supplies a fixed rectangle, so the
 * hit-testing contract is asserted without realizing a {@code JFrame} (impossible under the
 * suite's {@code -Djava.awt.headless=true}).
 *
 * <p><b>S14 / S15: the second pass-through region.</b> S14 let the chat through so the player can
 * type while paused; S15 (2026-10-12) widened that region to the whole three-column area — the
 * waiting list and Port table on the left and the comm log on the right stay usable too. What
 * stays dimmed and click-blocking is the top bar, the notification strip and the overlay's own
 * PAUSED box. The second half of this file pins exactly that, against stand-in rectangles laid out
 * like the real window at 1280×800.
 */
class PauseOverlayTest {

    /** A stand-in for the menu-bar strip: full width, 24 px tall, at the top. */
    private static final Rectangle MENU_BAR = new Rectangle(0, 0, 1280, 24);

    private static PauseOverlay overlay(Rectangle passThrough) {
        PauseOverlay overlay = new PauseOverlay(() -> { }, () -> passThrough);
        overlay.setBounds(0, 0, 1280, 800);
        return overlay;
    }

    @Test
    void clicksOnTheMenuBarStripFallThroughToTheMenuBar() {
        PauseOverlay overlay = overlay(MENU_BAR);

        assertFalse(overlay.contains(40, 8), "a click on the menu bar must not be swallowed while paused");
        assertFalse(overlay.contains(1279, 0), "the strip's far corner counts too");
    }

    @Test
    void everywhereBelowTheMenuBarStaysSwallowed() {
        PauseOverlay overlay = overlay(MENU_BAR);

        assertTrue(overlay.contains(40, 25), "the content area must still be blocked while paused");
        assertTrue(overlay.contains(640, 400), "...including the middle of the window");
        assertTrue(overlay.contains(0, 799));
    }

    @Test
    void withNoPassThroughRegionTheOverlayBehavesExactlyAsBefore() {
        // The one-arg constructor (and any frame with no menu bar) supplies an empty rectangle;
        // an empty Rectangle.contains is false for every point, so the whole surface stays solid.
        PauseOverlay overlay = overlay(new Rectangle());

        assertTrue(overlay.contains(40, 8));
        assertTrue(overlay.contains(640, 400));
    }

    @Test
    void pointsOutsideTheComponentAreStillOutside() {
        // contains() must not become a blanket "true" for the content area — the super call is
        // what keeps it a real bounds test.
        PauseOverlay overlay = overlay(MENU_BAR);

        assertFalse(overlay.contains(-1, 400));
        assertFalse(overlay.contains(640, 801));
    }

    // ============ S14 / S15: the three columns pass through, so the game stays usable while paused ============

    /** A stand-in for the three-column area: the full width between the top bar (which ends at
     * y = 60) and the notification strip (which starts at y = 760). */
    private static final Rectangle COLUMNS = new Rectangle(0, 60, 1280, 700);

    /** Where the default 20 / 40 / 40 split puts the three columns inside {@link #COLUMNS} (two
     * 10 px dividers between them). The overlay knows none of this — it is handed only the whole
     * area — these just say which column a tested point is in. */
    private static final Rectangle LEFT_COLUMN = new Rectangle(0, 60, 254, 700);
    private static final Rectangle CHAT_COLUMN = new Rectangle(264, 60, 503, 700);
    private static final Rectangle COMM_LOG_COLUMN = new Rectangle(777, 60, 503, 700);

    private static PauseOverlay overlayWithColumns() {
        PauseOverlay overlay = new PauseOverlay(() -> { }, () -> MENU_BAR, () -> COLUMNS);
        overlay.setBounds(0, 0, 1280, 800);
        overlay.doLayout(); // gives the PAUSED box its real bounds (the tests below need them)
        return overlay;
    }

    @Test
    void clicksOnTheChatFallThroughSoThePlayerCanTypeWhilePaused() {
        PauseOverlay overlay = overlayWithColumns();

        assertTrue(CHAT_COLUMN.contains(700, 740), "fixture: the point is in the chat column");
        assertFalse(overlay.contains(700, 740), "the chat input row must be reachable while paused");
        assertFalse(overlay.contains(265, 61), "the chat column's corner counts too");
        assertFalse(overlay.contains(40, 8), "and the menu bar still passes through");
    }

    @Test
    void clicksInTheLeftColumnFallThroughSoTheWaitingListStaysClickableWhilePaused() {
        // S15: picking the next vessel from "Waiting for you" is exactly what a player pauses
        // for. Before, this column was dimmed and dead while the chat beside it worked.
        PauseOverlay overlay = overlayWithColumns();

        assertTrue(LEFT_COLUMN.contains(100, 90) && LEFT_COLUMN.contains(100, 400), "fixture: left-column points");
        assertFalse(overlay.contains(100, 90), "a click on a waiting-list line must reach the list");
        assertFalse(overlay.contains(100, 400), "and the Port table below it is not blocked either");
        assertFalse(overlay.contains(0, 60), "the column's corner counts too");
    }

    @Test
    void clicksInTheCommLogColumnFallThroughSoTheLogCanBeReadAndFilteredWhilePaused() {
        // S15: the log is a read-only view; scrolling it and switching its filter change nothing
        // in the game, so a pause is no reason to block them.
        PauseOverlay overlay = overlayWithColumns();

        assertTrue(COMM_LOG_COLUMN.contains(1100, 400) && COMM_LOG_COLUMN.contains(1100, 80),
                "fixture: comm-log points");
        assertFalse(overlay.contains(1100, 400), "a click in the comm log must reach the log");
        assertFalse(overlay.contains(1100, 80), "and so must one on its filter row");
        assertFalse(overlay.contains(1279, 759), "the column's far corner counts too");
    }

    @Test
    void everythingElseStaysBlockedWhilePaused() {
        PauseOverlay overlay = overlayWithColumns();

        assertTrue(overlay.contains(200, 40), "the top bar stays blocked (its speed toggles included)");
        assertTrue(overlay.contains(1200, 30), "...along its whole width");
        assertTrue(overlay.contains(200, 780), "the notification strip stays blocked");
        assertTrue(overlay.contains(640, 59), "one pixel above the columns is still blocked");
        assertTrue(overlay.contains(640, 760), "and so is the first row of pixels below them");
    }

    @Test
    void theOverlaysOwnPausedBoxStaysClickableWhereItOverlapsTheColumns() {
        // The box is centred on the window, which in the three-column layout puts it over the
        // chat column; wherever it lands, its Resume button must not fall through to the panel
        // underneath.
        PauseOverlay overlay = overlayWithColumns();
        javax.swing.JButton resume = null;
        for (java.awt.Component child : overlay.getComponents()) {
            if (child instanceof javax.swing.JButton button) {
                resume = button;
            }
        }
        assertTrue(resume != null, "fixture: the overlay has its Resume button");
        Rectangle button = resume.getBounds();
        assertFalse(button.isEmpty(), "fixture: the button was laid out");
        int x = button.x + button.width / 2;
        int y = button.y + button.height / 2;
        assertTrue(COLUMNS.contains(x, y), "fixture: this layout really does put the button over the columns ("
                + button + ")");

        assertTrue(overlay.contains(x, y), "a click on Resume must reach the overlay, not the column under it");
    }

    @Test
    void theClickBlockingPatchIsTheWholeCaptionBoxNotJustTheButton() {
        // Caption, hint line and Resume button are one group with a margin: a click anywhere on
        // "PAUSED" must not type into the chat underneath it.
        PauseOverlay overlay = overlayWithColumns();
        Rectangle box = overlay.captionBox();
        assertFalse(box.isEmpty(), "fixture: the group was laid out");
        Rectangle overColumns = box.intersection(COLUMNS);
        assertFalse(overColumns.isEmpty(), "fixture: this layout puts the group over the columns");

        assertTrue(overlay.contains(overColumns.x + overColumns.width - 1, overColumns.y + 1),
                "the group's far corner over the columns is still the overlay's");
        assertFalse(overlay.contains(box.x + box.width + 5, box.y + 1),
                "just outside the group, the columns pass through again");
    }

    @Test
    void theCaptionStaysDimmedOverTheColumnsSoItsWhiteTextIsLegible() {
        // The columns are left undimmed so they can be read — but the PAUSED group sits over
        // them, and white text on a white transcript is not a pause indicator (adversarial
        // review, S14). Painted for real, headless.
        PauseOverlay overlay = overlayWithColumns();
        Rectangle box = overlay.captionBox();
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(1280, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 1280, 800);
            overlay.paint(g);
        } finally {
            g.dispose();
        }
        int white = 0xFFFFFF;
        // The padded corner of the group: inside the box, inside the columns, on none of the children.
        int cornerX = box.x + box.width - 3;
        int cornerY = box.y + 3;
        assertTrue(COLUMNS.contains(cornerX, cornerY), "fixture: the sampled corner is over the columns");

        assertTrue((image.getRGB(cornerX, cornerY) & 0xFFFFFF) != white,
                "behind the PAUSED group the scrim must stay, even over the columns");
        assertEquals(white, image.getRGB(CHAT_COLUMN.x + CHAT_COLUMN.width - 10, CHAT_COLUMN.y + 10) & 0xFFFFFF,
                "the rest of the chat is left undimmed");
        assertEquals(white, image.getRGB(100, 400) & 0xFFFFFF, "the left column is left undimmed (S15)");
        assertEquals(white, image.getRGB(1100, 400) & 0xFFFFFF, "the comm log is left undimmed (S15)");
        assertTrue((image.getRGB(100, 40) & 0xFFFFFF) != white, "the top bar is dimmed as before");
        assertTrue((image.getRGB(100, 780) & 0xFFFFFF) != white, "the notification strip is dimmed as before");
        assertEquals(white, image.getRGB(100, 10) & 0xFFFFFF, "the menu strip is left undimmed as before");
    }

    @Test
    void withNoColumnsRegionTheTwoArgOverlayBehavesExactlyAsBefore() {
        PauseOverlay overlay = overlay(MENU_BAR);

        assertTrue(overlay.contains(700, 740), "no columns region supplied: that area is blocked as it always was");
        assertTrue(overlay.contains(100, 400), "...the left column's too");
    }

    @Test
    void showingTheOverlayHeadlessDoesNotThrow() {
        // setVisible consults the focus owner to decide whether to take focus; with no window
        // and no focus owner (the test lane) that must simply fall back to the old behaviour.
        PauseOverlay overlay = overlayWithColumns();
        overlay.setVisible(true);
        overlay.setVisible(false);
        assertFalse(overlay.isVisible());
    }
}
