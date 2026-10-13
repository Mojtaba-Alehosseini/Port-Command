package it.unige.portcommand.bootstrap;

import it.unige.portcommand.assistant.AutopilotPacing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17a (ADR-21, playtest PT12): where the autopilot's pacing comes from. A LAUNCHED game paces
 * its moves; everything a test builds does not — which is what keeps the suite from waiting two
 * seconds per autopilot move, and it must stay true without any test having to remember it.
 */
class BootstrapConfigTest {

    private static final String PROPERTY = "autopilot.move.delay.ms";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void theFourArgumentFormEveryTestAndTheSmokeRunBuildsDoesNotPace() {
        BootstrapConfig config = new BootstrapConfig(18_099, false, "realtime", 300);

        assertEquals(0L, config.autopilotMoveDelayMillis());
        assertFalse(config.autopilotPacing().paced(), "a move is sent in the step that decided it");
        assertEquals(AutopilotPacing.NONE, config.autopilotPacing());
    }

    @Test
    void aLaunchedGamePacesItsAutopilotAtAboutTwoSecondsAMove() {
        assertEquals(2_000L, BootstrapConfig.defaults().autopilotMoveDelayMillis());
        assertEquals(2_000L, BootstrapConfig.fromSystemProperties().autopilotMoveDelayMillis(),
                "Main boots from the system properties, and with none set this is the game's pace");
        assertTrue(BootstrapConfig.fromSystemProperties().autopilotPacing().paced());
    }

    @Test
    void thePaceCanBeSetOrSwitchedOffFromTheCommandLine() {
        System.setProperty(PROPERTY, "750");
        assertEquals(new AutopilotPacing(750L), BootstrapConfig.fromSystemProperties().autopilotPacing());

        System.setProperty(PROPERTY, "0");
        assertFalse(BootstrapConfig.fromSystemProperties().autopilotPacing().paced());
    }

    @Test
    void aNegativeDelayIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new BootstrapConfig(18_099, false, "realtime", 300, -1L));
        assertThrows(IllegalArgumentException.class, () -> new AutopilotPacing(-1L));
    }
}
