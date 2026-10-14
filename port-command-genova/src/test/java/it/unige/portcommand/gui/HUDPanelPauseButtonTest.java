package it.unige.portcommand.gui;

import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S18b (playtest 2, PT4: "No pause button"; UI tree U13: the empty shell looks like a frozen game)
 * — the top bar's ⏸ / ▶ and its no-game cells. Headless: the bar is built, never shown.
 */
class HUDPanelPauseButtonTest {

    private static HUDPanel bar() throws Exception {
        HUDPanel[] built = new HUDPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            EventBus bus = new EventBus();
            built[0] = new HUDPanel(bus, new GameSpeedControl(new SimClock(300), bus, () -> false));
        });
        return built[0];
    }

    @Test
    @Timeout(10)
    void theButtonSitsBesideTheSpeedTogglesAndCallsThePauseFunnel() throws Exception {
        HUDPanel bar = bar();
        AtomicInteger toggled = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            JButton pause = bar.pauseButtonForTest();
            assertTrue(pause.getParent().getComponent(0) == pause, "first in the speed box");
            assertFalse(pause.isFocusable(), "a click never pulls the focus out of the chat input");
            bar.onPauseToggle(toggled::incrementAndGet);
            bar.showPauseState(false, true);
            pause.doClick(0);
        });
        assertEquals(1, toggled.get());
    }

    @Test
    @Timeout(10)
    void itShowsPauseWhileRunningAndPlayWhilePaused() throws Exception {
        HUDPanel bar = bar();
        SwingUtilities.invokeAndWait(() -> {
            JButton pause = bar.pauseButtonForTest();
            bar.showPauseState(false, true);
            assertEquals(HUDPanel.PAUSE_GLYPH, pause.getText(), "running: ⏸");
            assertEquals(HUDPanel.PAUSE_TOOLTIP, pause.getToolTipText());
            assertTrue(pause.isEnabled());

            bar.showPauseState(true, true);
            assertEquals(HUDPanel.RESUME_GLYPH, pause.getText(), "paused: ▶");
            assertEquals(HUDPanel.RESUME_TOOLTIP, pause.getToolTipText());

            bar.showPauseState(false, false);
            assertFalse(pause.isEnabled(), "nothing to toggle: no game, or a load in flight");
        });
    }

    @Test
    @Timeout(10)
    void aBarWithoutTheGameControlsHasNoPauseButton() throws Exception {
        HUDPanel[] built = new HUDPanel[1];
        SwingUtilities.invokeAndWait(() -> built[0] = new HUDPanel(new EventBus()));
        SwingUtilities.invokeAndWait(() -> {
            assertNull(built[0].pauseButtonForTest());
            built[0].showPauseState(true, true); // and asking it to show one is harmless
        });
    }

    /** U13: "Day 1 of 30  00:00" in the empty shell read as a game frozen at midnight. */
    @Test
    @Timeout(10)
    void theEmptyShellShowsADashInEveryCell() throws Exception {
        HUDPanel bar = bar();
        SwingUtilities.invokeAndWait(() -> {
            bar.showNoGame(true);
            assertEquals("—", bar.model().dayLabel());
            assertEquals("—", bar.model().timeLabel());
            assertEquals("Wallet: —", bar.model().walletLabel());
            assertEquals("Score: —", bar.model().scoreLabel());

            bar.showNoGame(false);
            assertTrue(bar.model().dayLabel().startsWith("Day "), "a game runs: the values are back");
        });
    }
}
