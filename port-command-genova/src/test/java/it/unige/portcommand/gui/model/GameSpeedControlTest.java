package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.GameSpeedChangedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14: the speed control's pure half — {@link GameSpeedModel} (choices, labels) and
 * {@link GameSpeedControl} (the one applier). CALLER_THREAD delivery, so every publish lands
 * synchronously; {@code Settings} is JVM-wide state, so it is put back around each case.
 */
class GameSpeedControlTest {

    private final EventBus bus = new EventBus();
    private final SimClock clock = new SimClock(3_600);
    private final List<Integer> announced = new ArrayList<>();
    private final AtomicBoolean locked = new AtomicBoolean(false);
    private final GameSpeedControl control = new GameSpeedControl(clock, bus, locked::get);

    @BeforeEach
    void subscribe() {
        Settings.applyLive(Settings.classpathDefaults());
        bus.subscribe(GameSpeedChangedEvent.class, e -> announced.add(e.speed()), DeliveryMode.CALLER_THREAD);
    }

    @AfterEach
    void restoreSettings() {
        Settings.applyLive(Settings.classpathDefaults());
    }

    @Test
    void theChoicesAreOneTwoFourLabelledWithATimesSign() {
        assertEquals(List.of(1, 2, 4), GameSpeedModel.options());
        assertEquals("×1", GameSpeedModel.label(1));
        assertEquals("×2", GameSpeedModel.label(2));
        assertEquals("×4", GameSpeedModel.label(4));
    }

    @Test
    void anUnknownMultiplierReadsAsTimesOne() {
        assertEquals(1, GameSpeedModel.normalise(0));
        assertEquals(1, GameSpeedModel.normalise(3));
        assertEquals(1, GameSpeedModel.normalise(-4));
        assertEquals(4, GameSpeedModel.normalise(4));
    }

    @Test
    void settingASpeedRePacesTheClockWritesTheSettingAndAnnouncesIt() {
        assertEquals(1, control.current());

        assertTrue(control.set(4));

        assertEquals(4, clock.speedMultiplier(), "the game clock runs at the chosen speed");
        assertEquals(4, Settings.load().gameSpeed(), "the live Settings carry it into the next save");
        assertEquals(List.of(4), announced);
        assertEquals(4, control.current());
    }

    @Test
    void speedChangesGamePaceOnly() {
        // The whole contract in one place: at x4 a real second covers four times the game time,
        // and a vessel's patience runs down exactly as fast as before.
        clock.conversationClock().open("nego-1", 120_000L);
        control.set(4);

        clock.advance(30_000);

        assertEquals(SimClock.simMillisDelta(30_000, 3_600) * 4, clock.nowSimMillis());
        assertEquals(90_000L, clock.conversationClock().view("nego-1").orElseThrow().remainingMillis());
        assertEquals(3_600L, clock.realSecondsPerGameDay(), "the x1 base day length is untouched");
        assertEquals(3_600L, Settings.load().realSecondsPerGameDay());
    }

    @Test
    void aSpeedThatIsNotOnOfferIsRefusedAndTheCurrentOneReAnnounced() {
        control.set(2);
        announced.clear();

        assertFalse(control.set(3));
        assertFalse(control.set(0));

        assertEquals(2, clock.speedMultiplier());
        assertEquals(2, Settings.load().gameSpeed());
        assertEquals(List.of(2, 2), announced, "a control showing the refused choice snaps back");
    }

    @Test
    void whileLockedNothingChangesAndTheControlsSnapBack() {
        // A load owns the world: the loader applies the SAVED speed, a click must not overwrite it.
        locked.set(true);

        assertFalse(control.set(4));

        assertEquals(1, clock.speedMultiplier());
        assertEquals(1, Settings.load().gameSpeed());
        assertEquals(List.of(1), announced);

        locked.set(false);
        assertTrue(control.set(4));
        assertEquals(4, clock.speedMultiplier());
    }

    @Test
    void settingTheSpeedLeavesTheOtherSettingsAlone() {
        Settings.applyLive(Settings.load().withRoundLimit(7).withAutopilotEnabled(true));

        control.set(2);

        assertEquals(7, Settings.load().roundLimit());
        assertTrue(Settings.load().autopilotEnabled());
    }

    // --- S16 (2026-10-13): the scenario-script lock --------------------------------------------------
    //
    // A scenario's scripted events are written for x1. While some are still to come the speed is
    // held there: the window forwards the bus's ScriptedWaveEvent to setScriptPending, and that is
    // all it does — everything that matters about the lock is in the control, and so here.

    @Test
    void theBaseSpeedIsTheSlowestOnOfferAndTheLockTooltipNamesIt() {
        assertEquals(1, GameSpeedModel.base());
        assertEquals("Speed is locked to ×1 while the scenario's scripted events are running",
                GameSpeedModel.SCRIPT_LOCK_TOOLTIP);
        assertTrue(GameSpeedModel.SCRIPT_LOCK_TOOLTIP.contains(GameSpeedModel.label(GameSpeedModel.base())));
    }

    @Test
    void whileTheScriptIsPendingASpeedChangeIsRefusedAndTheSpeedInForceReAnnounced() {
        assertFalse(control.scriptPending(), "a control starts unlocked");
        control.setScriptPending(true);
        assertTrue(control.scriptPending());
        announced.clear();

        assertFalse(control.set(4), "refused: the script is written for x1");
        assertFalse(control.set(2));

        assertEquals(1, clock.speedMultiplier(), "the game clock was not re-paced");
        assertEquals(1, Settings.load().gameSpeed(), "and nothing was written to the settings");
        assertEquals(List.of(1, 1), announced, "each refusal re-announces x1, so a control that moved snaps back");
        assertEquals(1, control.current());
    }

    @Test
    void onceTheScriptHasEndedASpeedChangeIsAcceptedAgain() {
        control.setScriptPending(true);
        assertFalse(control.set(4));

        control.setScriptPending(false);

        assertFalse(control.scriptPending());
        assertTrue(control.set(4));
        assertEquals(4, clock.speedMultiplier());
        assertEquals(4, Settings.load().gameSpeed());
    }

    @Test
    void engagingTheLockAboveTimesOneDropsToTimesOneFirstAndAnnouncesIt() {
        // The session already boots a pending script at x1; this is the backstop for a world that
        // reaches the lock faster than that.
        control.set(4);
        announced.clear();

        control.setScriptPending(true);

        assertEquals(1, clock.speedMultiplier(), "the lock never engages over a speed it would then forbid");
        assertEquals(1, Settings.load().gameSpeed(), "the next save records what is really in force");
        assertEquals(List.of(1), announced, "one announcement: the controls show x1, and re-read the lock");
        assertTrue(control.scriptPending());
        assertEquals(1, control.current());
    }

    @Test
    void theDropToTimesOneDoesNotAskTheOtherLock() {
        // The lock can arrive with a boot that is still LOADING — when the other lock refuses every
        // click. A speed left above x1 behind disabled controls could not be put right by the player.
        control.set(2);
        locked.set(true);

        control.setScriptPending(true);

        assertEquals(1, clock.speedMultiplier());
        assertEquals(1, Settings.load().gameSpeed());
    }

    @Test
    void everyChangeOfTheLockIsAnnouncedAndARepeatIsSilent() {
        // The top bar's toggles and the Game > Speed items re-read scriptPending() on every
        // GameSpeedChangedEvent: a change of the lock that announced nothing would leave them
        // enabled over a control that refuses, or disabled over one that would accept.
        control.setScriptPending(true);
        assertEquals(List.of(1), announced, "engaging at x1: the speed did not move, the lock did");

        control.setScriptPending(true);
        assertEquals(List.of(1), announced, "told the same thing again: nothing to announce");

        control.setScriptPending(false);
        assertEquals(List.of(1, 1), announced, "releasing is announced");

        control.setScriptPending(false);
        assertEquals(List.of(1, 1), announced,
                "\"nothing pending\" on a game with no script (every free-play boot) is silent");
    }

    @Test
    void releasingTheLockDoesNotBringTheOldSpeedBack() {
        // x4 before the script, x1 during it — and x1 after it, until the player chooses again.
        control.set(4);
        control.setScriptPending(true);

        control.setScriptPending(false);

        assertEquals(1, clock.speedMultiplier());
        assertEquals(1, Settings.load().gameSpeed());
    }

    @Test
    void theTwoLocksAreIndependent() {
        control.setScriptPending(true);
        locked.set(true);
        assertFalse(control.set(2));

        control.setScriptPending(false);
        assertFalse(control.set(2), "the script has ended, but a load still owns the world");

        locked.set(false);
        assertTrue(control.set(2));
    }
}
