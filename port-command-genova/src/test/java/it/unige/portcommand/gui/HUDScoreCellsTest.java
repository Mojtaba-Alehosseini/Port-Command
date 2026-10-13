package it.unige.portcommand.gui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.CallNextShipEvent;
import it.unige.portcommand.gui.events.ScoreChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.harbourmaster.financial.DayScore;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.DeliveryMode;
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
 * S17b (ADR-22) on the top bar: the score with its four parts on hover, today's profit against
 * the session's target, the scenario's name in place of "Day N of 30", and the "Next ship" button.
 */
class HUDScoreCellsTest {

    private final EventBus bus = new EventBus();
    private final SimClock clock = new SimClock(3_600);
    private boolean locked;
    private final GameSpeedControl control = new GameSpeedControl(clock, bus, () -> locked);

    @BeforeEach
    @AfterEach
    void defaultSettings() {
        Settings.applyLive(Settings.classpathDefaults());
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static JLabel label(HUDPanel hud, String name) {
        for (java.awt.Component c : hud.getComponents()) {
            if (name.equals(c.getName())) {
                return (JLabel) c;
            }
        }
        throw new AssertionError("no cell named " + name);
    }

    private static JLabel firstCell(HUDPanel hud) {
        return (JLabel) hud.getComponent(0);
    }

    private static DayScore score(double net, double target) {
        return new DayScore(1, net, target, 2, 3, 0, 1, 1.0);
    }

    @Test
    void beforeAnyGameTheCellsShowADash() {
        HUDPanel hud = new HUDPanel(bus, control);

        assertEquals("Today: —", label(hud, HUDPanel.NAME_PROFIT).getText());
        assertEquals("Score: —", label(hud, HUDPanel.NAME_SCORE).getText());
        assertNull(label(hud, HUDPanel.NAME_SCORE).getToolTipText());
    }

    @Test
    @Timeout(5)
    void theScoreItsFourPartsAndTheProfitFollowTheScoreEvent() throws Exception {
        HUDPanel hud = new HUDPanel(bus, control);

        bus.publish(new ScoreChangedEvent(score(3_640.0, 5_000.0), null));
        flush();

        assertEquals("Today: €3,640 / €5,000", label(hud, HUDPanel.NAME_PROFIT).getText());
        assertEquals("Score 75", label(hud, HUDPanel.NAME_SCORE).getText());
        String hover = label(hud, HUDPanel.NAME_SCORE).getToolTipText();
        assertNotNull(hover, "the four parts are on hover");
        assertTrue(hover.contains("Money") && hover.contains("18.2 / 25"), hover);
        assertTrue(hover.contains("Walk-ins") && hover.contains("2 deals of 3 ships"), hover);
        assertTrue(hover.contains("Safety") && hover.contains("unsafe moves 0 · blocked: 1"), hover);
        assertTrue(hover.contains("Reputation") && hover.contains("+1 today"), hover);
    }

    /** Decision E10: a scenario is one day long - "Day 1 of 30" was wrong for it. */
    @Test
    @Timeout(5)
    void duringAScenarioTheDayCellShowsTheScenarioAndItsOwnTarget() throws Exception {
        HUDPanel hud = new HUDPanel(bus, control);
        bus.publish(new SimClockTickEvent(8 * 3_600_000L, 1, 8, 0));

        bus.publish(new ScoreChangedEvent(score(-217.0, 3_500.0), "Tutorial — Calm Morning"));
        flush();

        assertEquals("Tutorial", firstCell(hud).getText());
        assertEquals("Tutorial — Calm Morning — the scenario ends at midnight", firstCell(hud).getToolTipText());
        assertEquals("Today: −€217 / €3,500", label(hud, HUDPanel.NAME_PROFIT).getText(),
                "the scenario's target, and a loss shown as a loss");

        // Past the first midnight the session is free play: the day is back.
        bus.publish(new SimClockTickEvent(24 * 3_600_000L + 60_000L, 2, 0, 1));
        bus.publish(new ScoreChangedEvent(new DayScore(2, 0.0, 5_000.0, 0, 0, 0, 0, 0.0), null));
        flush();

        assertEquals("Day 2 of " + Settings.load().maxGameDays(), firstCell(hud).getText());
        assertNull(firstCell(hud).getToolTipText());
    }

    /** A replaced world's score, target and scenario are not the next world's. */
    @Test
    @Timeout(5)
    void aNewWorldStartsFromADashUntilItAnnouncesItsOwnScore() throws Exception {
        HUDPanel hud = new HUDPanel(bus, control);
        bus.publish(new ScoreChangedEvent(score(3_640.0, 3_500.0), "Storm Event"));
        flush();
        assertEquals("Storm Event", firstCell(hud).getText());

        bus.publish(new GameLoadedEvent(1, 50_000.0, 70));
        flush();

        assertEquals("Score: —", label(hud, HUDPanel.NAME_SCORE).getText());
        assertEquals("Today: —", label(hud, HUDPanel.NAME_PROFIT).getText());
        assertTrue(firstCell(hud).getText().startsWith("Day "), firstCell(hud).getText());
    }

    // ---- "Next ship" (playtest 2, PT8) ----

    @Test
    @Timeout(5)
    void nextShipAsksOverTheBusAndNeverTakesTheFocus() throws Exception {
        HUDPanel hud = new HUDPanel(bus, control);
        List<CallNextShipEvent> asked = new CopyOnWriteArrayList<>();
        bus.subscribe(CallNextShipEvent.class, asked::add, DeliveryMode.CALLER_THREAD);
        JButton button = hud.nextShipButtonForTest();

        assertEquals("Next ship", button.getText());
        assertEquals(HUDPanel.NAME_NEXT_SHIP, button.getName());
        assertFalse(button.isFocusable(), "a click must not pull the focus out of the chat input");
        assertTrue(button.isEnabled());
        assertEquals("Call the next walk-in ship in now", button.getToolTipText());

        button.doClick();

        assertEquals(1, asked.size(), "one press, one request - the HarbourMaster decides");
    }

    @Test
    @Timeout(5)
    void whileAScenarioScriptRunsNextShipIsOffAndSaysWhy() throws Exception {
        HUDPanel hud = new HUDPanel(bus, control);
        List<CallNextShipEvent> asked = new CopyOnWriteArrayList<>();
        bus.subscribe(CallNextShipEvent.class, asked::add, DeliveryMode.CALLER_THREAD);
        JButton button = hud.nextShipButtonForTest();

        control.setScriptPending(true); // what the window does on ScriptedWaveEvent(true)
        flush();

        assertFalse(button.isEnabled());
        assertEquals("The scenario's own ships are still arriving", button.getToolTipText());
        button.doClick();
        assertEquals(0, asked.size(), "a disabled button asks for nothing");

        control.setScriptPending(false);
        flush();

        assertTrue(button.isEnabled(), "the script is done: ships can be called in");
        assertEquals("Call the next walk-in ship in now", button.getToolTipText());
    }

    @Test
    void aBarWithoutTheGameControlsHasNoNextShipButton() {
        assertNull(new HUDPanel(bus).nextShipButtonForTest());
    }
}
