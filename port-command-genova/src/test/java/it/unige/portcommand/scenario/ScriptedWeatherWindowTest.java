package it.unige.portcommand.scenario;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window in which a scenario's scripted weather is in force, read from the three SHIPPED
 * scenario files (S16, 2026-10-13, decision B5). The weather agent suspends its random steps
 * inside it ({@code WeatherStepperTest}, {@code ScenarioWeatherSeedTest}); this class pins where
 * it begins and ends. Red before S16 because nothing connected a scenario's weather events to
 * the random chain: there was no window to ask for.
 */
class ScriptedWeatherWindowTest {

    private static final long HOUR = 3_600_000L;
    /** Every shipped scenario starts on day 1 at 08:00. */
    private static final long START = 28_800_000L;

    private final ScenarioRegistry registry = new ScenarioRegistry();

    /**
     * Storm: the scripted storm is at 990 sim-seconds (08:16:30), the scripted clear at 1980
     * (08:33:00), and the window closes one game hour after the clear (09:33:00) — in ABSOLUTE
     * sim millis, which is what the weather agent compares its hour boundaries with. So the
     * 09:00 step is inside it and the 10:00 step is the first one after it.
     */
    @Test
    void storm_runsFromTheScriptedStormToOneGameHourAfterTheScriptedClear() {
        Scenario storm = registry.findByKey("storm").orElseThrow();
        assertEquals(START, storm.initialState().startSimMillis(), "fixture: day 1, 08:00");

        ScriptedWeatherWindow window = ScriptedWeatherWindow.of(storm).orElseThrow();

        assertEquals(START + 990_000L, window.fromSimMillis(), "08:16:30, the scripted storm");
        assertEquals(START + 1_980_000L + 3_600_000L, window.untilSimMillis(),
                "09:33:00, the scripted clear plus one game hour");
        assertTrue(window.covers(9 * HOUR), "the 09:00 step falls inside the window");
        assertFalse(window.covers(8 * HOUR), "08:00, the start, is before it");
        assertFalse(window.covers(10 * HOUR), "the 10:00 step is the first one after it");
    }

    /** The tutorial and the busy day script no weather change, so they have no window. */
    @Test
    void tutorialAndBusyDay_scriptNoWeatherAndHaveNoWindow() {
        assertTrue(ScriptedWeatherWindow.of(registry.findByKey("tutorial").orElseThrow()).isEmpty());
        assertTrue(ScriptedWeatherWindow.of(registry.findByKey("busy_day").orElseThrow()).isEmpty());
    }

    /** Free play has no scenario ({@code null}) and therefore no window. */
    @Test
    void freePlay_hasNoWindow() {
        assertTrue(ScriptedWeatherWindow.of(null).isEmpty());
    }

    /** Both ends belong to the window; the millisecond before and the one after do not. */
    @Test
    void covers_isInclusiveAtBothEnds() {
        ScriptedWeatherWindow window = new ScriptedWeatherWindow(1_000L, 2_000L);
        assertFalse(window.covers(999L));
        assertTrue(window.covers(1_000L), "the first instant is inside");
        assertTrue(window.covers(1_500L));
        assertTrue(window.covers(2_000L), "the last instant is inside");
        assertFalse(window.covers(2_001L));
    }

    /** The grace period after the last scripted change is one game hour. */
    @Test
    void theGracePeriodIsOneGameHour() {
        assertEquals(HOUR, ScriptedWeatherWindow.GRACE_SIM_MILLIS);
    }
}
