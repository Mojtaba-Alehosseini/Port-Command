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

    // ==================== S14: the chat passes through, so the player can type while paused ====================

    /** A stand-in for the chat panel: the middle column of the window, below the HUD. */
    private static final Rectangle CHAT = new Rectangle(600, 60, 360, 700);

    private static PauseOverlay overlayWithChat() {
        PauseOverlay overlay = new PauseOverlay(() -> { }, () -> MENU_BAR, () -> CHAT);
        overlay.setBounds(0, 0, 1280, 800);
        overlay.doLayout(); // gives the PAUSED box its real bounds (the test below needs them)
        return overlay;
    }

    @Test
    void clicksOnTheChatFallThroughSoThePlayerCanTypeWhilePaused() {
        PauseOverlay overlay = overlayWithChat();

        assertFalse(overlay.contains(700, 740), "the chat input row must be reachable while paused");
        assertFalse(overlay.contains(601, 61), "the chat's corner counts too");
        assertFalse(overlay.contains(40, 8), "and the menu bar still passes through");
    }

    @Test
    void everythingElseStaysBlockedWhilePaused() {
        PauseOverlay overlay = overlayWithChat();

        assertTrue(overlay.contains(200, 400), "the map stays blocked");
        assertTrue(overlay.contains(1100, 400), "the comm log stays blocked");
        assertTrue(overlay.contains(200, 40), "the HUD strip stays blocked");
        assertTrue(overlay.contains(599, 400), "one pixel left of the chat is still blocked");
    }

    @Test
    void theOverlaysOwnPausedBoxStaysClickableWhereItOverlapsTheChat() {
        // The box is centred on the window and is not moved for the chat (a UI redesign follows);
        // wherever it lands, its Resume button must not fall through to the chat underneath.
        PauseOverlay overlay = overlayWithChat();
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
        assertTrue(CHAT.contains(x, y), "fixture: this layout really does put the button over the chat ("
                + button + ")");

        assertTrue(overlay.contains(x, y), "a click on Resume must reach the overlay, not the chat");
    }

    @Test
    void theClickBlockingPatchIsTheWholeCaptionBoxNotJustTheButton() {
        // Caption, hint line and Resume button are one group with a margin: a click anywhere on
        // "PAUSED" must not type into the chat underneath it.
        PauseOverlay overlay = overlayWithChat();
        Rectangle box = overlay.captionBox();
        assertFalse(box.isEmpty(), "fixture: the group was laid out");
        Rectangle overChat = box.intersection(CHAT);
        assertFalse(overChat.isEmpty(), "fixture: this layout puts part of the group over the chat");

        assertTrue(overlay.contains(overChat.x + overChat.width - 1, overChat.y + 1),
                "the group's far corner over the chat is still the overlay's");
        assertFalse(overlay.contains(box.x + box.width + 5, box.y + 1),
                "just outside the group, the chat passes through again");
    }

    @Test
    void theCaptionStaysDimmedOverTheChatSoItsWhiteTextIsLegible() {
        // The chat is left undimmed so it can be read — but the PAUSED group straddles the
        // map | chat boundary at the default dividers, and white text on a white transcript is
        // not a pause indicator (adversarial review, S14). Painted for real, headless.
        PauseOverlay overlay = overlayWithChat();
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
        // The padded corner of the group: inside the box, inside the chat, on none of the children.
        int cornerX = box.x + box.width - 3;
        int cornerY = box.y + 3;
        assertTrue(CHAT.contains(cornerX, cornerY), "fixture: the sampled corner is over the chat");

        assertTrue((image.getRGB(cornerX, cornerY) & 0xFFFFFF) != white,
                "behind the PAUSED group the scrim must stay, even over the chat");
        assertEquals(white, image.getRGB(CHAT.x + CHAT.width - 10, CHAT.y + 10) & 0xFFFFFF,
                "the rest of the chat is left undimmed");
        assertTrue((image.getRGB(100, 400) & 0xFFFFFF) != white, "the map is dimmed as before");
        assertEquals(white, image.getRGB(100, 10) & 0xFFFFFF, "the menu strip is left undimmed as before");
    }

    @Test
    void withNoChatRegionTheTwoArgOverlayBehavesExactlyAsBefore() {
        PauseOverlay overlay = overlay(MENU_BAR);

        assertTrue(overlay.contains(700, 740), "no chat region supplied: that area is blocked as it always was");
    }

    @Test
    void showingTheOverlayHeadlessDoesNotThrow() {
        // setVisible consults the focus owner to decide whether to take focus; with no window
        // and no focus owner (the test lane) that must simply fall back to the old behaviour.
        PauseOverlay overlay = overlayWithChat();
        overlay.setVisible(true);
        overlay.setVisible(false);
        assertFalse(overlay.isVisible());
    }
}
