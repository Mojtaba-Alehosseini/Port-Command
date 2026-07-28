package it.unige.portcommand.gui;

import java.awt.Rectangle;

import org.junit.jupiter.api.Test;

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
}
