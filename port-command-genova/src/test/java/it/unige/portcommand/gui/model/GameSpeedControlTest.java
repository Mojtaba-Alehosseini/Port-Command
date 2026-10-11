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
}
