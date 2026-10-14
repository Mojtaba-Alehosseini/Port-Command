package it.unige.portcommand.gui;

import java.awt.Rectangle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link MainWindow#clampToUsableBounds} (audit P1-13 / demo-laptop risk C-19).
 *
 * <p>The window used to be a flat {@code setSize(1280, 800)} + {@code setLocationRelativeTo(null)},
 * which overflows a scaled desktop and centres on the FULL screen rect, so on the demo laptop at
 * 125-150% scaling the NotificationStrip sat under the taskbar. The arithmetic is extracted as a
 * pure static taking the usable rectangle because
 * {@code GraphicsEnvironment.getMaximumWindowBounds()} throws under {@code -Djava.awt.headless=true}
 * (both Gradle test lanes set it) — same split as {@code makePopupsHeavyweight}/
 * {@code MenuPopupWeightTest}. {@code java.awt.Rectangle} itself constructs fine headless.
 */
class WindowSizingTest {

    private static final int PREF_W = 1280;
    private static final int PREF_H = 800;
    private static final int MIN_W = 800;
    private static final int MIN_H = 600;

    private static Rectangle clamp(Rectangle usable) {
        return MainWindow.clampToUsableBounds(usable, PREF_W, PREF_H, MIN_W, MIN_H);
    }

    @Test
    void anUnscaled1080pDesktopKeepsThePreferredSizeAndCentres() {
        // 1920x1080 minus a 40px bottom taskbar.
        Rectangle usable = new Rectangle(0, 0, 1920, 1040);
        Rectangle w = clamp(usable);

        assertEquals(PREF_W, w.width, "1280 fits, so it is kept");
        assertEquals(PREF_H, w.height, "800 fits, so it is kept");
        assertEquals((1920 - 1280) / 2, w.x, "centred horizontally");
        assertEquals((1040 - 800) / 2, w.y, "centred vertically within the USABLE area");
    }

    @Test
    void a125PercentScaledDesktopFitsButOnlyIfCentredWithinTheUsableArea() {
        // 1920x1080 at 125% reports 1536x864 logical; a 48px scaled taskbar leaves 816 usable.
        // 1280x800 genuinely FITS here - so at 125% the defect was never the size, it was the
        // centring: the old setLocationRelativeTo(null) centred on the full 864 (y=32), putting
        // the bottom at 832, i.e. 16px of NotificationStrip behind the taskbar.
        Rectangle usable = new Rectangle(0, 0, 1536, 816);
        Rectangle w = clamp(usable);

        assertEquals(PREF_W, w.width, "1280 fits in 1536");
        assertEquals(PREF_H, w.height, "800 fits in 816 - no clamping needed on this desktop");

        int oldCentredBottom = (864 - PREF_H) / 2 + PREF_H;
        assertTrue(oldCentredBottom > usable.height,
                "centring on the FULL screen really did overflow (bottom=" + oldCentredBottom + ")");
        assertTrue(w.y + w.height <= usable.height,
                "centring within the usable area does not, got bottom=" + (w.y + w.height));
    }

    @Test
    void a150PercentScaledDesktopClampsBothAxes() {
        // 1920x1080 at 150% reports 1280x720; minus a 58px scaled taskbar -> 662 usable height.
        Rectangle usable = new Rectangle(0, 0, 1280, 662);
        Rectangle w = clamp(usable);

        assertEquals(1280, w.width, "exactly fits");
        assertEquals(662, w.height, "clamped down from 800 - this is the NotificationStrip fix");
        assertTrue(w.y + w.height <= usable.y + usable.height,
                "the window bottom must stay above the taskbar, got bottom=" + (w.y + w.height)
                        + " usable bottom=" + (usable.y + usable.height));
    }

    @Test
    void aTaskbarAtTheTopOffsetsTheOriginRatherThanOverlapping() {
        // Taskbar docked at the top: usable area starts at y=48, not y=0.
        Rectangle usable = new Rectangle(0, 48, 1920, 1032);
        Rectangle w = clamp(usable);

        assertTrue(w.y >= 48, "the window top must not slide under a top-docked taskbar, got y=" + w.y);
        assertTrue(w.y + w.height <= 48 + 1032, "and must not overflow the bottom either");
    }

    @Test
    void aSecondaryMonitorOriginIsHonoured() {
        // getMaximumWindowBounds() can report a non-zero origin on a multi-monitor desktop.
        Rectangle usable = new Rectangle(1920, 0, 1600, 900);
        Rectangle w = clamp(usable);

        assertTrue(w.x >= 1920, "must be placed on the reported device, got x=" + w.x);
        assertEquals(1920 + (1600 - 1280) / 2, w.x, "centred within that device");
    }

    @Test
    void theMinimumWinsOnADesktopTooSmallForIt() {
        // Deliberate: a sub-800x600 window crushes the three-panel layout, and setMinimumSize
        // would override the cap anyway - overflowing a tiny screen is the better failure.
        Rectangle usable = new Rectangle(0, 0, 640, 480);
        Rectangle w = clamp(usable);

        assertEquals(MIN_W, w.width, "floored at the minimum width");
        assertEquals(MIN_H, w.height, "floored at the minimum height");
        assertEquals(0, w.x, "and pinned to the origin rather than centred to a negative x");
        assertEquals(0, w.y, "and pinned to the origin rather than centred to a negative y");
    }

    /** S18b (UI tree U18): a desktop shorter than the minimum height gets a shorter minimum. */
    @Test
    void theMinimumHeightGivesWayToAShortDesktop() {
        assertEquals(MainWindow.MIN_HEIGHT, MainWindow.minimumHeightFor(new Rectangle(0, 0, 1920, 1040)));
        assertEquals(574, MainWindow.minimumHeightFor(new Rectangle(0, 0, 1093, 574)),
                "1366x768 at 125% with its taskbar: the whole usable height, no more");
        assertEquals(MainWindow.MIN_HEIGHT, MainWindow.minimumHeightFor(new Rectangle()),
                "no usable area reported: the old minimum");
        Rectangle placed = MainWindow.clampToUsableBounds(new Rectangle(0, 0, 1093, 574), 1280, 800, 1000,
                MainWindow.minimumHeightFor(new Rectangle(0, 0, 1093, 574)));
        assertEquals(574, placed.height, "the window fits the desktop's height");
        assertEquals(0, placed.y);
    }

    @Test
    void theOldUnclampedBehaviourWouldHaveFailedTheseCases() {
        // Non-vacuity: the pre-fix code was setSize(1280, 800) + centre-on-full-screen. Against
        // the 150% desktop the un-clamped 800px height puts the window bottom 69px below the
        // usable area even after centring pulls it up - the reported
        // NotificationStrip-under-the-taskbar symptom. (Centred on the real 720px screen rather
        // than the 662px usable area it is worse still: 98px.)
        Rectangle usable = new Rectangle(0, 0, 1280, 662);
        int unclampedBottom = (662 - 800) / 2 + 800;
        assertTrue(unclampedBottom > usable.height,
                "the old behaviour really did overflow (bottom=" + unclampedBottom + ")");

        Rectangle w = clamp(usable);
        assertTrue(w.y + w.height <= usable.height, "the clamp fixes exactly that");
    }
}
