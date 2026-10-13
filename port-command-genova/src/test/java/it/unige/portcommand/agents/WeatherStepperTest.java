package it.unige.portcommand.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import it.unige.portcommand.agents.InitArgs.WeatherInitArgs.ScriptedWeather;
import it.unige.portcommand.harbourmaster.MovementGate;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.scenario.ScriptedWeatherWindow;
import it.unige.portcommand.util.RandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weather chain's stepping, driven with nothing but a list of clock readings (S16,
 * 2026-10-13, decisions B5, B6, B8). No container, no clock object, no agent: the stepper is
 * told what time it is, exactly as {@code WeatherEvolutionBehaviour} tells it.
 *
 * <p>Every test here is red on the pre-S16 code for the plain reason that the class did not
 * exist; each test's comment says which pre-S16 BEHAVIOUR it would also contradict. Real Prolog
 * is behind the last test only (it asks the movement gate which rule a reading breaks).
 */
class WeatherStepperTest {

    private static final long HOUR = WeatherStepper.STEP_SIM_MILLIS;
    private static final long MINUTE = 60_000L;
    /** Day 1, 08:00 — where every shipped scenario starts. */
    private static final long EIGHT = 8 * HOUR;
    private static final long MAIN_SEED = 42L;
    private static final long DETAIL_SEED = 43L;

    private static final WeatherSnapshot OPENING = new WeatherSnapshot(18, "good", 0.5, "sunny", EIGHT);

    @BeforeAll
    static void initProlog() {
        PrologEngine.getInstance().init();
    }

    /** A {@link Random} that counts what the stepper takes from it. */
    private static final class CountingRandom extends Random {
        private static final long serialVersionUID = 1L;
        private int doubles;
        private int ints;

        CountingRandom(long seed) {
            super(seed);
        }

        @Override
        public double nextDouble() {
            doubles++;
            return super.nextDouble();
        }

        @Override
        public int nextInt(int bound) {
            ints++;
            return super.nextInt(bound);
        }
    }

    private static WeatherStepper stepper(Random main, Random detail, ScriptedWeatherWindow window,
                                          long builtAtSimMillis) {
        return new WeatherStepper(TransitionMatrix.defaults(), main, detail, window, List.of(),
                builtAtSimMillis);
    }

    // ---- B6: one step per game hour, on the hour, whatever the tick cadence ----

    /**
     * B6. The clock is read the way a real session reads it: every game minute at &times;1,
     * then every fourth minute and off the hour (a ticker still armed at the &times;1 period
     * after a switch to &times;4), then fifty times at the same instant (a pause: the ticker
     * fires, the clock stands still), then once three game hours later (one very late look),
     * then every minute again. Twelve game hours pass.
     *
     * <p>Pins: exactly twelve steps — one per game hour — each stamped on its hour, and after
     * EVERY reading the weather is what a second stepper with the same seed has at that hour
     * when it is simply stepped hour by hour. Pre-S16 the step was one per tick of a ticker
     * armed in wall time: the same session lost the steps of the &times;4 stretch, dropped the
     * ones that fell into the pause and stamped none on the hour.
     */
    @Test
    void speedChangesPausesAndLateTicksNeitherLoseNorAddAStep() {
        WeatherStepper hourly = stepper(new CountingRandom(MAIN_SEED), new CountingRandom(DETAIL_SEED),
                null, EIGHT);
        WeatherSnapshot[] atHour = new WeatherSnapshot[21];
        atHour[8] = OPENING;
        for (int hour = 9; hour <= 20; hour++) {
            atHour[hour] = hourly.advanceTo(hour * HOUR, atHour[hour - 1]);
            assertNotSame(atHour[hour - 1], atHour[hour], "reference: a step at " + hour + ":00");
            assertEquals(hour * HOUR, atHour[hour].simTime(), "reference: stamped on the hour");
        }

        List<Long> clock = new ArrayList<>();
        long now = EIGHT;
        for (int i = 0; i < 180; i++) {          // x1: 08:01 ... 11:00, every game minute
            now += MINUTE;
            clock.add(now);
        }
        now += 150_000L;                         // x4 on a stale period: 11:02:30 ...
        clock.add(now);
        for (int i = 0; i < 45; i++) {           // ... every fourth minute, to 14:02:30
            now += 4 * MINUTE;
            clock.add(now);
        }
        for (int i = 0; i < 50; i++) {           // paused: fifty looks at a clock that stands still
            clock.add(now);
        }
        now += 3 * HOUR;                         // one late look: 17:02:30
        clock.add(now);
        while (now < 20 * HOUR + 30_000L) {      // x1 again, to 20:00:30
            now += MINUTE;
            clock.add(now);
        }
        assertEquals(20 * HOUR + 30_000L, now, "fixture: the session ends half a minute past 20:00");

        CountingRandom main = new CountingRandom(MAIN_SEED);
        CountingRandom detail = new CountingRandom(DETAIL_SEED);
        WeatherStepper live = stepper(main, detail, null, EIGHT);
        WeatherSnapshot cell = OPENING;
        List<Long> stampsSeen = new ArrayList<>();
        for (long reading : clock) {
            WeatherSnapshot next = live.advanceTo(reading, cell);
            if (next != cell) {
                stampsSeen.add(next.simTime());
            }
            cell = next;
            assertEquals(atHour[(int) (reading / HOUR)], cell,
                    "at sim-ms " + reading + " the weather must be the hour-by-hour chain's");
        }

        assertEquals(12, main.doubles, "one state roll per game hour, 09:00 to 20:00 - no more, no fewer");
        assertEquals(12, main.ints, "one wind draw per game hour");
        assertEquals(24, detail.doubles, "two detail draws per game hour");
        // The late look took 15:00, 16:00 and 17:00 in one call and handed back the last of them.
        assertEquals(List.of(9 * HOUR, 10 * HOUR, 11 * HOUR, 12 * HOUR, 13 * HOUR, 14 * HOUR,
                17 * HOUR, 18 * HOUR, 19 * HOUR, 20 * HOUR), stampsSeen,
                "every reading handed back is stamped with a full hour, never with the tick's own time");
    }

    /**
     * B6. A stepper built at 08:20 (a game loaded at 08:20) takes its first step at 09:00 and
     * its second at 10:00. Pre-S16 the first step came one ticker period after boot — 09:20 —
     * and every later one twenty minutes past the hour.
     */
    @Test
    void theFirstStepAfterAStartAtTwentyPastIsTheNextFullHour() {
        long builtAt = EIGHT + 20 * MINUTE;
        WeatherStepper s = stepper(new CountingRandom(MAIN_SEED), new CountingRandom(DETAIL_SEED), null, builtAt);
        WeatherSnapshot opening = new WeatherSnapshot(18, "good", 0.5, "sunny", builtAt);

        assertEquals(9 * HOUR, s.nextStepAtSimMillis());
        assertSame(opening, s.advanceTo(9 * HOUR - 1, opening), "one millisecond before 09:00: no step");

        WeatherSnapshot first = s.advanceTo(9 * HOUR, opening);
        assertNotSame(opening, first, "09:00 is the first step");
        assertEquals(9 * HOUR, first.simTime());

        assertSame(first, s.advanceTo(9 * HOUR + 20 * MINUTE, first),
                "09:20 - one period after the start, where the old ticker stepped - is not a step");
        assertEquals(10 * HOUR, s.nextStepAtSimMillis());
    }

    /** B6. Built exactly ON an hour, the first step is the NEXT hour: strictly after, not at. */
    @Test
    void aStepperBuiltOnTheHourDoesNotStepAtThatHour() {
        WeatherStepper s = stepper(new CountingRandom(MAIN_SEED), new CountingRandom(DETAIL_SEED), null, EIGHT);
        assertSame(OPENING, s.advanceTo(EIGHT, OPENING));
        assertEquals(9 * HOUR, s.nextStepAtSimMillis());
    }

    /**
     * B6, the guard. A clock that jumps a hundred game hours is caught up {@value
     * WeatherStepper#MAX_STEPS_PER_CALL} steps per call, and no boundary is skipped: after the
     * second call all hundred have been taken.
     */
    @Test
    void aHugeClockJumpIsCaughtUpInBoundedCallsWithoutSkippingAStep() {
        CountingRandom main = new CountingRandom(MAIN_SEED);
        WeatherStepper s = stepper(main, new CountingRandom(DETAIL_SEED), null, 0L);
        WeatherSnapshot start = new WeatherSnapshot(18, "good", 0.5, "sunny", 0L);
        long now = 100 * HOUR;

        WeatherSnapshot first = s.advanceTo(now, start);
        assertEquals(WeatherStepper.MAX_STEPS_PER_CALL, main.doubles);
        assertEquals(WeatherStepper.MAX_STEPS_PER_CALL * HOUR, first.simTime());

        WeatherSnapshot second = s.advanceTo(now, first);
        assertEquals(100, main.doubles, "the rest follows on the next call");
        assertEquals(100 * HOUR, second.simTime());
        assertSame(second, s.advanceTo(now, second), "and then there is nothing left to take");
        assertEquals(101 * HOUR, s.nextStepAtSimMillis());
    }

    // ---- B5: suspended inside the scripted-weather window ----

    /**
     * B5. With a window covering 11:00, 12:00 and 13:00, those three steps hand back the very
     * same reading (the script's weather is not touched) — and still take every draw a normal
     * step takes, on both streams.
     *
     * <p>What exactly is equal afterwards: a second stepper with the same seeds and NO window is
     * stepped alongside, and for the three hours inside the window its result is thrown away by
     * hand, so it too goes on from the frozen reading. From then on the two must agree on every
     * field at every hour, because they have spent the same draws from the same reading. A
     * stepper that skipped the draws of a suspended step would fail both the draw counts and
     * that comparison; the pre-S16 chain had no window at all and simply overwrote the reading.
     */
    @Test
    void stepsInsideTheScriptedWindowLeaveTheReadingAloneButSpendTheirDraws() {
        ScriptedWeatherWindow window =
                new ScriptedWeatherWindow(10 * HOUR + 30 * MINUTE, 13 * HOUR + 30 * MINUTE);
        CountingRandom main = new CountingRandom(MAIN_SEED);
        CountingRandom detail = new CountingRandom(DETAIL_SEED);
        WeatherStepper windowed = stepper(main, detail, window, EIGHT);
        WeatherStepper free = stepper(new CountingRandom(MAIN_SEED), new CountingRandom(DETAIL_SEED),
                null, EIGHT);

        WeatherSnapshot a = OPENING;
        WeatherSnapshot b = OPENING;
        int suspended = 0;
        for (int hour = 9; hour <= 20; hour++) {
            long boundary = hour * HOUR;
            WeatherSnapshot nextA = windowed.advanceTo(boundary, a);
            WeatherSnapshot nextB = free.advanceTo(boundary, b);
            if (window.covers(boundary)) {
                suspended++;
                assertSame(a, nextA, hour + ":00 is inside the window: the reading must not change");
                assertEquals(10 * HOUR, nextA.simTime(), "it is still the reading the 10:00 step left");
                nextB = b; // the free chain's outcome for this hour, discarded by hand
            } else {
                assertEquals(boundary, nextA.simTime(), hour + ":00 is outside the window: a step");
            }
            a = nextA;
            b = nextB;
            assertEquals(b, a, "after " + hour + ":00");
            assertEquals(hour - 8, main.doubles, "state rolls spent after " + hour + ":00");
            assertEquals(hour - 8, main.ints, "wind draws spent after " + hour + ":00");
            assertEquals(2 * (hour - 8), detail.doubles, "detail draws spent after " + hour + ":00");
        }
        assertEquals(3, suspended, "fixture: 11:00, 12:00 and 13:00 are inside the window");
    }

    /** B5. The window's two ends are inside it: a step exactly at either end is suspended. */
    @Test
    void aStepExactlyOnEitherEndOfTheWindowIsSuspended() {
        ScriptedWeatherWindow window = new ScriptedWeatherWindow(9 * HOUR, 10 * HOUR);
        WeatherStepper s = stepper(new CountingRandom(MAIN_SEED), new CountingRandom(DETAIL_SEED), window, EIGHT);

        assertSame(OPENING, s.advanceTo(9 * HOUR, OPENING), "09:00 is the window's first instant");
        assertSame(OPENING, s.advanceTo(10 * HOUR, OPENING), "10:00 is the window's last instant");
        WeatherSnapshot after = s.advanceTo(11 * HOUR, OPENING);
        assertNotSame(OPENING, after, "11:00 is the first step that takes effect again");
        assertEquals(11 * HOUR, after.simTime());
    }

    // ---- the legacy init-args override ----

    /**
     * The init-args scripted snapshot keeps its meaning — it replaces the Markov draw of the
     * first step at or after its sim-minute and draws nothing — on the new cadence: that step
     * is the next full hour, and the snapshot is stamped with it. (Pre-S16 it was applied by
     * whichever wall tick came first after its minute.)
     */
    @Test
    void aLegacyScriptedOverrideReplacesTheNextHourlyStepAndDrawsNothing() {
        CountingRandom main = new CountingRandom(MAIN_SEED);
        CountingRandom detail = new CountingRandom(DETAIL_SEED);
        WeatherSnapshot scripted = new WeatherSnapshot(32, "poor", 3.5, "stormy", 0L);
        WeatherStepper s = new WeatherStepper(TransitionMatrix.defaults(), main, detail, null,
                List.of(new ScriptedWeather(8, scripted)), 0L);
        WeatherSnapshot start = new WeatherSnapshot(18, "good", 0.5, "sunny", 0L);

        assertSame(start, s.advanceTo(24 * MINUTE, start),
                "sim-minute 24 is past the override's minute 8, but no hour boundary has been reached");

        WeatherSnapshot applied = s.advanceTo(HOUR, start);
        assertEquals(new WeatherSnapshot(32, "poor", 3.5, "stormy", HOUR), applied);
        assertEquals(0, main.doubles, "an override is not a Markov step: no state roll");
        assertEquals(0, main.ints, "no wind draw");
        assertEquals(0, detail.doubles, "no detail draw");

        WeatherSnapshot next = s.advanceTo(2 * HOUR, applied);
        assertEquals(2 * HOUR, next.simTime());
        assertEquals(1, main.doubles, "the hour after it is an ordinary step again");
    }

    // ---- B8: swell and visibility vary, from their own stream ----

    /**
     * B8. Five thousand game hours with the streams the game builds for seed 42
     * ({@code "weather"} and {@code "weather-detail"}), next to the same main stream WITHOUT a
     * detail stream.
     *
     * <p>Pins: (1) at least one stormy hour has a swell over 4.0 m, and the movement gate then
     * names R17 for a cargo vessel — pre-S16 the stormy swell was a fixed 3.5 m, so R17 could
     * never fire from generated weather; (2) at least one cloudy hour has {@code poor}
     * visibility, and the gate's verdict for a cargo vessel is then exactly {@code [R16]} —
     * pre-S16 visibility was only ever {@code poor} together with {@code stormy} (R19); (3) the
     * state and the wind are identical, hour for hour, with and without the detail stream — the
     * detail draws cannot shift the sequence every seed has always had; (4) the ranges: stormy
     * swell 3.0 to 5.0 m in tenths, cloudy swell and sunny readings as before.
     */
    @Test
    void swellAndVisibilityVaryWithoutMovingTheStateAndWindSequence() {
        WeatherStepper detailed = stepper(new RandomSource(42L).forStream("weather"),
                new RandomSource(42L).forStream("weather-detail"), null, 0L);
        WeatherStepper plain = stepper(new RandomSource(42L).forStream("weather"), null, null, 0L);
        WeatherSnapshot a = new WeatherSnapshot(18, "good", 0.5, "sunny", 0L);
        WeatherSnapshot b = a;

        WeatherSnapshot stormOverTheSwellLimit = null;
        WeatherSnapshot cloudyWithPoorVisibility = null;
        int cloudy = 0;
        int cloudyPoor = 0;
        for (int i = 1; i <= 5_000; i++) {
            a = detailed.advanceTo(i * HOUR, a);
            b = plain.advanceTo(i * HOUR, b);
            assertEquals(b.state(), a.state(), "state at step " + i);
            assertEquals(b.wind(), a.wind(), "wind at step " + i);
            switch (a.state()) {
                case "stormy" -> {
                    assertEquals("poor", a.visibility(), "stormy visibility stays poor");
                    assertTrue(a.swell() >= 3.0 && a.swell() <= 5.0, "stormy swell in range: " + a.swell());
                    assertEquals(Math.rint(a.swell() * 10.0), a.swell() * 10.0, 1e-9,
                            "stormy swell has one decimal: " + a.swell());
                    assertEquals(3.5, b.swell(), "without a detail stream the stormy swell is the old 3.5 m");
                    if (a.swell() > 4.0 && stormOverTheSwellLimit == null) {
                        stormOverTheSwellLimit = a;
                    }
                }
                case "cloudy" -> {
                    cloudy++;
                    assertEquals(1.5, a.swell(), "cloudy swell stays 1.5 m");
                    assertEquals("fair", b.visibility(), "without a detail stream cloudy is always fair");
                    if ("poor".equals(a.visibility())) {
                        cloudyPoor++;
                        if (cloudyWithPoorVisibility == null) {
                            cloudyWithPoorVisibility = a;
                        }
                    } else {
                        assertEquals("fair", a.visibility());
                    }
                }
                default -> {
                    assertEquals("sunny", a.state());
                    assertEquals("good", a.visibility());
                    assertEquals(0.5, a.swell());
                }
            }
        }

        assertNotNull(stormOverTheSwellLimit, "5000 hours must contain a storm with a swell over 4.0 m");
        MovementGate.Verdict inTheSwell = MovementGate.check("cargo_vessel", reading(stormOverTheSwellLimit));
        assertTrue(inTheSwell.ruleIds().contains("R17"),
                "R17 fires from generated weather: " + stormOverTheSwellLimit + " -> " + inTheSwell);

        assertNotNull(cloudyWithPoorVisibility, "5000 hours must contain a cloudy hour with poor visibility");
        MovementGate.Verdict inTheMurk = MovementGate.check("cargo_vessel", reading(cloudyWithPoorVisibility));
        assertEquals(List.of("R16"), inTheMurk.ruleIds(),
                "R16 fires on its own: " + cloudyWithPoorVisibility + " -> " + inTheMurk);

        // "Occasionally": one cloudy hour in ten, give or take.
        assertTrue(cloudyPoor * 20 > cloudy && cloudyPoor * 5 < cloudy,
                "poor visibility in " + cloudyPoor + " of " + cloudy + " cloudy hours");
    }

    private static MovementGate.Reading reading(WeatherSnapshot w) {
        return new MovementGate.Reading(w.wind(), w.visibility(), w.swell(), w.state());
    }
}
