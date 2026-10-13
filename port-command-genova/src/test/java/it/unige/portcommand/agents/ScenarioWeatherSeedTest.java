package it.unige.portcommand.agents;

import java.util.ArrayList;
import java.util.List;

import it.unige.portcommand.scenario.Scenario;
import it.unige.portcommand.scenario.ScenarioRegistry;
import it.unige.portcommand.scenario.ScriptedWeatherWindow;
import it.unige.portcommand.scenario.events.WeatherChangeScriptedEvent;
import it.unige.portcommand.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weather the three SHIPPED scenarios actually get on day 1, from their own files: the
 * scenario's seed, its opening reading, its scripted-weather window, and the stepper built the
 * way {@code WeatherAgent.onSetup} builds it (S16, 2026-10-13, decisions B5 and B6).
 *
 * <p>The literals were not taken from a run of this code. They were worked out by hand from
 * {@code java.util.Random}'s published algorithm and {@code RandomSource.forStream}'s seed
 * derivation (the tutorial and busy-day rows twice, independently, for the pre-S16 chain), so
 * they pin the sequence from outside. If a literal is ever wrong the first thing to suspect is
 * a change to the main stream's draw order: one {@code nextDouble()} for the state, then one
 * {@code nextInt} for the wind, per step, nothing else.
 */
class ScenarioWeatherSeedTest {

    private static final long HOUR = WeatherStepper.STEP_SIM_MILLIS;

    private final ScenarioRegistry registry = new ScenarioRegistry();

    /** The stepper as the weather agent builds it when {@code scenario} starts. */
    private static WeatherStepper stepperFor(Scenario scenario) {
        RandomSource source = new RandomSource(scenario.randomSeed());
        return new WeatherStepper(TransitionMatrix.defaults(), source.forStream("weather"),
                source.forStream("weather-detail"), ScriptedWeatherWindow.of(scenario).orElse(null),
                List.of(), scenario.initialState().startSimMillis());
    }

    /**
     * B5, the storm scenario (seed 20260320). From the scripted clear's reading (24 kn / good /
     * 1.0 m / cloudy, set at 08:33) the day is stepped hour by hour to midnight.
     *
     * <p>Pins: the 09:00 step, the only one inside the window 08:16:30 … 09:33, leaves the
     * scripted reading untouched; the first step after it, 10:00, is not {@code stormy}; and
     * the whole of day 1 from 10:00 to 23:00, state and wind, as literals — with no storm in
     * it. Pre-S16 the first random step after the scripted clear rolled 0.9532, which is
     * {@code stormy} from any state: a second, three-hour storm (30, 36, 42 kn) right after the
     * "Storm clearing" banner. That roll is still drawn — it is the suspended 09:00 step — and
     * the day goes on from the second.
     *
     * <p>Worth knowing when reading the literal: 10:00 is cloudy at 30 kn. That is not a storm,
     * but it is exactly a tanker's wind limit, so tankers wait for that one hour (R15) and the
     * 11:00 reading, 24 kn, is the clear.
     */
    @Test
    void storm_theScriptedWeatherIsLeftAloneAndNoSecondStormFollowsIt() {
        Scenario storm = registry.findByKey("storm").orElseThrow();
        assertEquals(20260320L, storm.randomSeed(), "fixture: the shipped storm seed");
        long start = storm.initialState().startSimMillis();
        assertEquals(8 * HOUR, start, "fixture: the storm scenario starts on day 1 at 08:00");
        ScriptedWeatherWindow window = ScriptedWeatherWindow.of(storm).orElseThrow();

        List<WeatherChangeScriptedEvent> scripted = storm.events().stream()
                .filter(WeatherChangeScriptedEvent.class::isInstance)
                .map(WeatherChangeScriptedEvent.class::cast).toList();
        WeatherChangeScriptedEvent clear = scripted.get(scripted.size() - 1);
        WeatherSnapshot cleared = new WeatherSnapshot(clear.windKn(), clear.visibility(), clear.swell(),
                clear.state(), start + clear.simTimeSeconds() * 1000L);
        assertEquals(new WeatherSnapshot(24, "good", 1.0, "cloudy", 8 * HOUR + 33 * 60_000L), cleared,
                "fixture: the scripted clear, set at 08:33");

        assertTrue(window.covers(9 * HOUR), "fixture: 09:00 is inside the window");
        assertFalse(window.covers(10 * HOUR), "fixture: 10:00 is the first step after it");

        WeatherStepper stepper = stepperFor(storm);
        WeatherSnapshot w = cleared;
        List<String> states = new ArrayList<>();
        List<Integer> winds = new ArrayList<>();
        for (int hour = 9; hour <= 24; hour++) {
            long boundary = hour * HOUR;
            WeatherSnapshot next = stepper.advanceTo(boundary, w);
            if (window.covers(boundary)) {
                assertSame(w, next, "the " + hour + ":00 step is inside the scripted window and must "
                        + "leave the scripted reading exactly as it is");
            } else {
                assertEquals(boundary, next.simTime(), "the " + hour + ":00 step is stamped on its hour");
                if (hour <= 23) {
                    states.add(next.state());
                    winds.add(next.wind());
                }
            }
            w = next;
        }

        assertNotEquals("stormy", states.get(0), "10:00, the first random step after the scripted clear");
        assertEquals(List.of("cloudy", "cloudy", "sunny", "sunny", "sunny", "sunny", "sunny",
                        "cloudy", "sunny", "sunny", "sunny", "sunny", "sunny", "sunny"), states,
                "storm, day 1, 10:00 to 23:00: the states");
        assertEquals(List.of(30, 24, 18, 12, 9, 11, 15, 21, 19, 14, 17, 14, 10, 16), winds,
                "storm, day 1, 10:00 to 23:00: the wind in knots");
        assertFalse(states.contains("stormy"), "no storm for the rest of the day");
    }

    /**
     * B6 and B8, the other two scenarios (no scripted weather, so no window): the state and
     * wind sequence of their seeds is the one the chain produced before S16, hour for hour —
     * the steps moved onto the full hours and gained a second random stream, and neither
     * shifted a single draw of the first. Twelve hours each, 09:00 to 20:00.
     *
     * <p>Busy day's fourth hour is the flapping case the release margin exists for: cloudy at
     * 31 kn at 12:00 (over a tanker's 30), back to 25 kn at 13:00.
     */
    @Test
    void tutorialAndBusyDay_keepTheStateAndWindSequenceTheirSeedsAlwaysHad() {
        assertDayOne("tutorial", 20260101L, new WeatherSnapshot(8, "good", 0.3, "sunny", 8 * HOUR),
                List.of("cloudy", "cloudy", "cloudy", "sunny", "sunny", "sunny",
                        "sunny", "sunny", "sunny", "cloudy", "sunny", "sunny"),
                List.of(15, 15, 21, 15, 9, 15, 14, 19, 13, 19, 18, 12));
        assertDayOne("busy_day", 20260214L, new WeatherSnapshot(15, "good", 0.5, "sunny", 8 * HOUR),
                List.of("cloudy", "cloudy", "cloudy", "cloudy", "cloudy", "cloudy",
                        "cloudy", "cloudy", "sunny", "sunny", "cloudy", "sunny"),
                List.of(21, 26, 25, 31, 25, 21, 17, 18, 18, 12, 18, 12));
    }

    private void assertDayOne(String key, long expectedSeed, WeatherSnapshot expectedOpening,
                              List<String> expectedStates, List<Integer> expectedWinds) {
        Scenario scenario = registry.findByKey(key).orElseThrow();
        assertEquals(expectedSeed, scenario.randomSeed(), "fixture: the shipped " + key + " seed");
        assertTrue(ScriptedWeatherWindow.of(scenario).isEmpty(), key + " scripts no weather");
        Scenario.WeatherInit init = scenario.initialState().weatherOverride();
        WeatherSnapshot w = new WeatherSnapshot(init.windKn(), init.visibility(), init.swell(), init.state(),
                scenario.initialState().startSimMillis());
        assertEquals(expectedOpening, w, "fixture: the shipped " + key + " opening weather at 08:00");

        WeatherStepper stepper = stepperFor(scenario);
        List<String> states = new ArrayList<>();
        List<Integer> winds = new ArrayList<>();
        for (int hour = 9; hour <= 20; hour++) {
            w = stepper.advanceTo(hour * HOUR, w);
            assertEquals(hour * HOUR, w.simTime(), key + ": the " + hour + ":00 step is stamped on its hour");
            states.add(w.state());
            winds.add(w.wind());
        }
        assertEquals(expectedStates, states, key + ", day 1, 09:00 to 20:00: the states");
        assertEquals(expectedWinds, winds, key + ", day 1, 09:00 to 20:00: the wind in knots");
    }
}
