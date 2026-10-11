package it.unige.portcommand.gui;

import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.GameSpeedChangedEvent;
import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * S14: the HUD's ×1 / ×2 / ×4 toggles. Deliberately thin — the choices, the applying and the
 * refusing are {@code GameSpeedControl}'s ({@code GameSpeedControlTest}); what is pinned here is
 * only that the widgets call it and follow {@link GameSpeedChangedEvent}. Headless; EDT delivery
 * is flushed with {@code invokeAndWait}.
 */
class HUDSpeedTogglesTest {

    private final EventBus bus = new EventBus();
    private final SimClock clock = new SimClock(3_600);
    private boolean locked;

    @BeforeEach
    @AfterEach
    void defaultSettings() {
        Settings.applyLive(Settings.classpathDefaults());
    }

    private HUDPanel hud() {
        return new HUDPanel(bus, new GameSpeedControl(clock, bus, () -> locked));
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static JToggleButton toggle(HUDPanel hud, int speed) {
        for (java.awt.Component box : hud.getComponents()) {
            if (box instanceof java.awt.Container container) {
                for (java.awt.Component c : container.getComponents()) {
                    if ((HUDPanel.NAME_SPEED_PREFIX + speed).equals(c.getName())) {
                        return (JToggleButton) c;
                    }
                }
            }
        }
        return null;
    }

    @Test
    @Timeout(5)
    void threeTogglesNamedLabelledAndNotFocusable() {
        HUDPanel hud = hud();

        for (int speed : new int[] {1, 2, 4}) {
            JToggleButton toggle = toggle(hud, speed);
            assertNotNull(toggle, "a toggle for x" + speed);
            assertEquals("×" + speed, toggle.getText());
            assertFalse(toggle.isFocusable(), "a click must never pull focus out of the chat input");
        }
        assertEquals(1, hud.selectedSpeedForTest(), "opens on the speed in force");
    }

    @Test
    @Timeout(5)
    void clickingAToggleRePacesTheGameAndSelectsIt() throws Exception {
        HUDPanel hud = hud();

        hud.clickSpeedForTest(4);
        flush();

        assertEquals(4, clock.speedMultiplier());
        assertEquals(4, Settings.load().gameSpeed());
        assertEquals(4, hud.selectedSpeedForTest());
    }

    @Test
    @Timeout(5)
    void theTogglesFollowASpeedSetElsewhere() throws Exception {
        // The Game > Speed menu (Ctrl+1/2/4), or a load restoring the saved speed.
        HUDPanel hud = hud();

        bus.publish(new GameSpeedChangedEvent(2));
        flush();

        assertEquals(2, hud.selectedSpeedForTest());
    }

    @Test
    @Timeout(5)
    void aRefusedClickSnapsBackToTheSpeedInForce() throws Exception {
        // doClick() selects the toggle on its own; while a load owns the world the control
        // refuses, and re-announces what is really in force.
        HUDPanel hud = hud();
        locked = true;

        hud.clickSpeedForTest(4);
        flush();

        assertEquals(1, clock.speedMultiplier());
        assertEquals(1, hud.selectedSpeedForTest());
    }

    @Test
    @Timeout(5)
    void aHudOpenedMidGameShowsTheSpeedAlreadyInForce() {
        clock.setSpeedMultiplier(2);

        assertEquals(2, hud().selectedSpeedForTest());
    }

    @Test
    @Timeout(5)
    void theOneArgHudHasNoSpeedControl() {
        assertEquals(0, new HUDPanel(bus).selectedSpeedForTest());
    }

    @Test
    @Timeout(5)
    void theTogglesStopFollowingOnceTheHudIsRemoved() throws Exception {
        HUDPanel hud = hud();
        hud.removeNotify();

        bus.publish(new GameSpeedChangedEvent(4));
        flush();

        assertEquals(1, hud.selectedSpeedForTest(), "the subscription is cancelled with the others");
    }
}
