package it.unige.portcommand.gui;

import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.GameSpeedChangedEvent;
import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.gui.model.GameSpeedModel;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14: the HUD's ×1 / ×2 / ×4 toggles. Deliberately thin — the choices, the applying and the
 * refusing are {@code GameSpeedControl}'s ({@code GameSpeedControlTest}); what is pinned here is
 * only that the widgets call it and follow {@link GameSpeedChangedEvent}. Headless; EDT delivery
 * is flushed with {@code invokeAndWait}.
 *
 * <p>S16 (2026-10-13): and that they show the scenario-script lock — disabled, with the reason as
 * their tooltip, for as long as the control says the script is pending.
 */
class HUDSpeedTogglesTest {

    private static final int[] SPEEDS = {1, 2, 4};

    private final EventBus bus = new EventBus();
    private final SimClock clock = new SimClock(3_600);
    private boolean locked;
    private final GameSpeedControl control = new GameSpeedControl(clock, bus, () -> locked);

    @BeforeEach
    @AfterEach
    void defaultSettings() {
        Settings.applyLive(Settings.classpathDefaults());
    }

    private HUDPanel hud() {
        return new HUDPanel(bus, control);
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

        for (int speed : SPEEDS) {
            JToggleButton toggle = toggle(hud, speed);
            assertNotNull(toggle, "a toggle for x" + speed);
            assertEquals("×" + speed, toggle.getText());
            assertFalse(toggle.isFocusable(), "a click must never pull focus out of the chat input");
            assertTrue(toggle.isEnabled(), "no script is pending: the toggles work");
            assertNull(toggle.getToolTipText(), "and have nothing to explain");
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
        // S16: that refusal keeps its snap-back look. Only the script lock disables the toggles —
        // a load lasts a moment, and the bar is not told about it.
        for (int speed : SPEEDS) {
            assertTrue(toggle(hud, speed).isEnabled(), "x" + speed + " stays enabled while a load is in flight");
            assertNull(toggle(hud, speed).getToolTipText());
        }
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

    // --- S16: the scenario-script lock ---------------------------------------------------------------

    @Test
    @Timeout(5)
    void whileTheScriptIsPendingTheTogglesAreDisabledAndSayWhy() throws Exception {
        HUDPanel hud = hud();

        control.setScriptPending(true); // what the window does on ScriptedWaveEvent(true)
        flush();

        for (int speed : SPEEDS) {
            JToggleButton toggle = toggle(hud, speed);
            assertFalse(toggle.isEnabled(), "x" + speed + " must be disabled while the script is pending");
            assertEquals("Speed is locked to ×1 while the scenario's scripted events are running",
                    toggle.getToolTipText(), "a control that just snapped back would read as a bug");
            assertEquals(GameSpeedModel.SCRIPT_LOCK_TOOLTIP, toggle.getToolTipText());
            assertFalse(toggle.isFocusable(), "disabled or not, it never takes the focus");
        }
        assertEquals(1, hud.selectedSpeedForTest(), "x1 stays shown as the speed in force");

        hud.clickSpeedForTest(4); // a click on a disabled toggle does nothing at all
        flush();
        assertEquals(1, clock.speedMultiplier());
        assertEquals(1, hud.selectedSpeedForTest());
    }

    @Test
    @Timeout(5)
    void whenTheScriptEndsTheTogglesWorkAgain() throws Exception {
        HUDPanel hud = hud();
        control.setScriptPending(true);
        flush();
        assertFalse(toggle(hud, 4).isEnabled(), "precondition: locked");

        control.setScriptPending(false); // ScriptedWaveEvent(false): the last scripted event fired
        flush();

        for (int speed : SPEEDS) {
            assertTrue(toggle(hud, speed).isEnabled(), "x" + speed + " is enabled again");
            assertNull(toggle(hud, speed).getToolTipText(), "and the explanation is gone with the lock");
        }
        hud.clickSpeedForTest(4);
        flush();
        assertEquals(4, clock.speedMultiplier());
        assertEquals(4, hud.selectedSpeedForTest());
    }

    @Test
    @Timeout(5)
    void aLockEngagedAtTimesFourShowsTimesOneSelectedBehindTheDisabledToggles() throws Exception {
        HUDPanel hud = hud();
        hud.clickSpeedForTest(4);
        flush();
        assertEquals(4, hud.selectedSpeedForTest(), "precondition: the game runs at x4");

        control.setScriptPending(true);
        flush();

        assertEquals(1, clock.speedMultiplier(), "the control dropped to x1 before locking");
        assertEquals(1, hud.selectedSpeedForTest(), "and the bar shows it: never x4 behind a lock");
        assertFalse(toggle(hud, 1).isEnabled());
        assertFalse(toggle(hud, 4).isEnabled());
    }

    @Test
    @Timeout(5)
    void aHudBuiltWhileTheScriptIsPendingStartsDisabled() {
        // A window that opens on a scenario already under way: no event will come to tell it.
        control.setScriptPending(true);

        HUDPanel hud = hud();

        for (int speed : SPEEDS) {
            assertFalse(toggle(hud, speed).isEnabled(), "x" + speed);
            assertEquals(GameSpeedModel.SCRIPT_LOCK_TOOLTIP, toggle(hud, speed).getToolTipText());
        }
    }
}
