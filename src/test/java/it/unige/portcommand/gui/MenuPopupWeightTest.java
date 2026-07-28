package it.unige.portcommand.gui;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit C-04, second half — found by the adversarial review of the first half (2026-07-27).
 *
 * <p>Passing the menu-bar strip through {@code PauseOverlay.contains} makes the menus OPEN while
 * paused, and that is all it does. Swing's default popup is <b>lightweight</b>:
 * {@code LightWeightPopup} adds the {@code JPopupMenu} into the frame's own {@code JLayeredPane},
 * and {@code JRootPane.addImpl} forces the glass pane to child index 0 — above the entire layered
 * pane. So the dropdown would render under the PAUSED scrim and its items would land where
 * {@code contains()} still reports true: <b>Save and Load, the two items {@code reflectMode()}
 * deliberately keeps enabled in PAUSED, would still be unclickable.</b> The strip fix alone would
 * have looked correct in {@code PauseOverlayTest} and failed in front of the examiner.
 *
 * <p>A heavyweight popup is its own window, outside the root pane's z-order, so the glass pane
 * cannot cover it. This test pins that every menu built by {@code MainWindow} has it — the property
 * is checkable headlessly even though the frame it belongs to is not (only top-level Window
 * realization is gated by {@code -Djava.awt.headless=true}, not component construction).
 */
class MenuPopupWeightTest {

    private static JMenuBar barWith(String... menuNames) {
        JMenuBar bar = new JMenuBar();
        for (String name : menuNames) {
            JMenu menu = new JMenu(name);
            menu.add(new JMenuItem("an item"));
            bar.add(menu);
        }
        return bar;
    }

    @Test
    void everyMenuDropdownIsHeavyweightSoThePausedGlassPaneCannotCoverIt() {
        JMenuBar bar = barWith("Game", "Settings", "Debug", "Help");

        // Precondition: Swing's default really is lightweight, so the assertion below is not vacuous.
        assertTrue(bar.getMenu(0).getPopupMenu().isLightWeightPopupEnabled(),
                "fixture sanity: Swing defaults to lightweight popups, which is the whole problem");

        MainWindow.makePopupsHeavyweight(bar);

        for (int i = 0; i < bar.getMenuCount(); i++) {
            assertFalse(bar.getMenu(i).getPopupMenu().isLightWeightPopupEnabled(),
                    "menu '" + bar.getMenu(i).getText() + "' would render under the PAUSED glass pane");
        }
    }

    @Test
    void anEmptyOrRaggedMenuBarDoesNotBreakTheSweep() {
        // getMenu(i) returns null for a non-JMenu component (a separator, a Box.createHorizontalGlue()),
        // which MainWindow's bar does not have today but easily could.
        JMenuBar bar = barWith("Game");
        bar.add(javax.swing.Box.createHorizontalGlue());

        MainWindow.makePopupsHeavyweight(bar);
        MainWindow.makePopupsHeavyweight(new JMenuBar());

        assertFalse(bar.getMenu(0).getPopupMenu().isLightWeightPopupEnabled());
    }
}
