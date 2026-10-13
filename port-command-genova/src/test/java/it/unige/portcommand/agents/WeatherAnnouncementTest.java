package it.unige.portcommand.agents;

import java.util.Optional;

import it.unige.portcommand.agents.WeatherAlertPolicy.Direction;
import it.unige.portcommand.behaviours.coordination.PeriodicWeatherBroadcastBehaviour;
import it.unige.portcommand.behaviours.coordination.PeriodicWeatherBroadcastBehaviour.Announcement;
import it.unige.portcommand.prolog.PrologEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT the weather broadcast announces, cycle after cycle (S16, 2026-10-13): the rule in
 * {@code PeriodicWeatherBroadcastBehaviour.announce} plus the two things the behaviour starts
 * from — the boot reading as its baseline, and the "a worsening is outstanding" flag that is
 * already up when the world boots in bad weather.
 *
 * <p>{@link WeatherAlertPolicyTest} pins the DIRECTION of a change between two readings; that
 * policy is stateless and stays so. This class pins what becomes news. It lives here and not
 * next to the behaviour because the behaviour's package is catalogue-counted and can hold no
 * test class. Real Prolog behind every verdict, as in the policy test.
 */
class WeatherAnnouncementTest {

    @BeforeAll
    static void initProlog() {
        PrologEngine.getInstance().init();
    }

    private static WeatherSnapshot snap(int wind, String visibility, double swell, String state) {
        return new WeatherSnapshot(wind, visibility, swell, state, 0L);
    }

    private static WeatherSnapshot cloudy(int wind) {
        return snap(wind, "fair", 1.5, "cloudy");
    }

    /**
     * The behaviour's own bookkeeping, and nothing else: the baseline and the flag are seeded
     * from the boot reading (its constructor), and every cycle feeds them to {@code announce}
     * and keeps what comes back ({@code onSimTick}).
     */
    private static final class Broadcasts {
        private WeatherSnapshot lastObserved;
        private boolean worseningOutstanding;

        Broadcasts(WeatherSnapshot bootReading) {
            this.lastObserved = bootReading;
            this.worseningOutstanding =
                    PeriodicWeatherBroadcastBehaviour.worseningOutstandingAtBoot(bootReading);
        }

        /** One broadcast cycle carrying {@code reading}: the alert it sends, if any. */
        Optional<Direction> cycle(WeatherSnapshot reading) {
            Announcement a = PeriodicWeatherBroadcastBehaviour.announce(
                    worseningOutstanding, lastObserved, reading);
            worseningOutstanding = a.worseningOutstanding();
            lastObserved = reading;
            return a.alert();
        }
    }

    private static final Optional<Direction> NOTHING = Optional.empty();
    private static final Optional<Direction> WORSENING = Optional.of(Direction.WORSENED);
    private static final Optional<Direction> CLEARING = Optional.of(Direction.CLEARED);

    /**
     * The case the flag exists for. Cloudy wind wanders 26 → 28 → 26 kn: 28 crosses no band,
     * holds nobody and is announced by nobody, so the way back down is not news. Before this
     * rule the stateless policy made it "Weather clearing: 26 kn" (28 is not calm, 26 is).
     */
    @Test
    void aClearWithNoWorseningBeforeItIsNotAnnounced() {
        Broadcasts b = new Broadcasts(cloudy(26));
        assertEquals(NOTHING, b.cycle(cloudy(28)), "26 → 28 kn: no band crossed");
        assertEquals(Optional.of(Direction.CLEARED), WeatherAlertPolicy.transition(cloudy(28), cloudy(26)),
                "fixture: the policy does call 28 → 26 kn a clear");
        assertEquals(NOTHING, b.cycle(cloudy(26)), "28 → 26 kn: a clear nobody was waiting for");
        assertEquals(NOTHING, b.cycle(cloudy(28)));
        assertEquals(NOTHING, b.cycle(cloudy(27)), "and again, however often the wind wanders");
    }

    /**
     * The path the stateless policy must keep clearing on: a worsening at 31 kn is announced,
     * the wind drops to 28 (still inside a tanker's release margin: nothing), then to 27 — the
     * clearing, announced once. The wander that follows announces nothing more.
     */
    @Test
    void aClearAfterAnAnnouncedWorseningIsAnnouncedOnce() {
        Broadcasts b = new Broadcasts(cloudy(26));
        assertEquals(WORSENING, b.cycle(cloudy(31)), "26 → 31 kn crosses the 30-kn band");
        assertEquals(NOTHING, b.cycle(cloudy(28)), "31 → 28 kn: a held tanker still waits");
        assertEquals(CLEARING, b.cycle(cloudy(27)), "28 → 27 kn: every type may move again");
        assertEquals(NOTHING, b.cycle(cloudy(28)));
        assertEquals(NOTHING, b.cycle(cloudy(26)), "the worsening has been answered: no second clearing");
    }

    /** Two clears in a row after one worsening: only the first is news. */
    @Test
    void ofTwoClearsInARowOnlyTheFirstIsAnnounced() {
        Broadcasts b = new Broadcasts(snap(18, "good", 0.5, "sunny"));
        assertEquals(WORSENING, b.cycle(snap(20, "poor", 0.5, "sunny")), "visibility collapses");
        assertEquals(CLEARING, b.cycle(snap(20, "good", 0.5, "sunny")), "and recovers: the first clear");
        assertEquals(NOTHING, b.cycle(cloudy(29)), "29 kn: not calm, but no band crossed");
        assertEquals(NOTHING, b.cycle(cloudy(25)), "the second clear follows no worsening");
    }

    /** A storm that gets worse twice is announced twice and cleared once. */
    @Test
    void everyWorseningIsAnnouncedAndOneClearingAnswersThemAll() {
        Broadcasts b = new Broadcasts(cloudy(22));
        assertEquals(WORSENING, b.cycle(snap(32, "poor", 4.0, "stormy")));
        assertEquals(WORSENING, b.cycle(snap(36, "poor", 4.0, "stormy")), "the next band up");
        assertEquals(NOTHING, b.cycle(snap(33, "poor", 4.0, "stormy")), "a band drop inside the storm");
        assertEquals(CLEARING, b.cycle(snap(24, "good", 1.0, "cloudy")));
        assertEquals(NOTHING, b.cycle(snap(18, "good", 0.5, "sunny")));
    }

    /**
     * A world that boots or loads in a storm. Its first broadcast carries the very reading it
     * booted with and is silent — no alert is re-fired by a load taken in mid-storm, which is
     * what the old {@code null} baseline was for — but the flag is already up, so the storm's
     * end IS announced although no worsening was ever announced in this world.
     */
    @Test
    void aWorldThatBootsInAStormIsSilentAtFirstAndStillAnnouncesTheClearing() {
        WeatherSnapshot storm = snap(40, "poor", 3.5, "stormy");
        assertTrue(PeriodicWeatherBroadcastBehaviour.worseningOutstandingAtBoot(storm));
        Broadcasts b = new Broadcasts(storm);
        assertEquals(NOTHING, b.cycle(snap(40, "poor", 3.5, "stormy")), "the boot reading again: no alert");
        assertEquals(NOTHING, b.cycle(snap(38, "poor", 3.5, "stormy")), "a band drop inside the storm");
        assertEquals(CLEARING, b.cycle(snap(20, "good", 0.5, "sunny")), "the storm ends: announced");
        assertEquals(NOTHING, b.cycle(cloudy(28)));
        assertEquals(NOTHING, b.cycle(cloudy(26)), "and only once");
    }

    /**
     * The baseline is the boot reading, so a change that is already there at the FIRST broadcast
     * is announced. With the old {@code null} baseline the first broadcast was the baseline
     * itself and announced nothing, whatever it carried: a first hourly step that fell before
     * the first 15-sim-minute tick could turn the weather unsafe without a word.
     */
    @Test
    void aChangeAlreadyThereAtTheFirstBroadcastIsAnnouncedAgainstTheBootReading() {
        Broadcasts b = new Broadcasts(snap(18, "good", 0.5, "sunny"));
        assertEquals(WORSENING, b.cycle(snap(30, "poor", 4.2, "stormy")),
                "the first broadcast after boot, one step later");
    }

    /** The flag at boot is "the boot reading is not calm for every vessel type" — and no more. */
    @Test
    void theFlagAtBootIsRaisedExactlyWhenTheBootReadingIsNotCalm() {
        assertFalse(PeriodicWeatherBroadcastBehaviour.worseningOutstandingAtBoot(snap(18, "good", 0.5, "sunny")));
        assertFalse(PeriodicWeatherBroadcastBehaviour.worseningOutstandingAtBoot(cloudy(27)), "27 kn is calm");
        assertTrue(PeriodicWeatherBroadcastBehaviour.worseningOutstandingAtBoot(cloudy(28)),
                "28 kn: a tanker held at 30 would still be waiting");
        assertTrue(PeriodicWeatherBroadcastBehaviour.worseningOutstandingAtBoot(snap(20, "poor", 1.5, "cloudy")));
        assertTrue(PeriodicWeatherBroadcastBehaviour.worseningOutstandingAtBoot(snap(20, "good", 4.1, "cloudy")));
        assertFalse(PeriodicWeatherBroadcastBehaviour.worseningOutstandingAtBoot(null), "no reading: nothing owed");

        // So a game loaded at 28 kn announces the drop to 27: a tanker may have been held.
        Broadcasts b = new Broadcasts(cloudy(28));
        assertEquals(CLEARING, b.cycle(cloudy(27)));
    }

    /** The rule itself, one cycle at a time: what comes back for each transition and flag. */
    @Test
    void announceReturnsTheAlertAndTheFlagItLeavesBehind() {
        WeatherSnapshot calm = cloudy(26);
        WeatherSnapshot windy = cloudy(31);

        assertEquals(new Announcement(NOTHING, false),
                PeriodicWeatherBroadcastBehaviour.announce(false, calm, cloudy(25)), "routine, flag down");
        assertEquals(new Announcement(NOTHING, true),
                PeriodicWeatherBroadcastBehaviour.announce(true, windy, cloudy(33)), "routine, flag stays up");
        assertEquals(new Announcement(WORSENING, true),
                PeriodicWeatherBroadcastBehaviour.announce(false, calm, windy), "a worsening raises the flag");
        assertEquals(new Announcement(WORSENING, true),
                PeriodicWeatherBroadcastBehaviour.announce(true, windy, cloudy(36)), "and keeps it up");
        assertEquals(new Announcement(CLEARING, false),
                PeriodicWeatherBroadcastBehaviour.announce(true, windy, calm), "a clear with the flag up: news");
        assertEquals(new Announcement(NOTHING, false),
                PeriodicWeatherBroadcastBehaviour.announce(false, cloudy(28), calm), "with the flag down: not");
        assertEquals(new Announcement(NOTHING, false),
                PeriodicWeatherBroadcastBehaviour.announce(false, null, windy),
                "no baseline at all: the policy's first-reading rule still holds");
    }
}
