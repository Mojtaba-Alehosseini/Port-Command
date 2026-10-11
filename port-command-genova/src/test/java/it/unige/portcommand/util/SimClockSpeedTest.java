package it.unige.portcommand.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14: the speed control (×1 / ×2 / ×4) changes how fast GAME time runs and nothing else, and the
 * conversation clock a {@link SimClock} owns is fed real time that the speed never touches
 * (ADR-18). Exact-integer, like {@code SimClockTest} — the clock is driven by hand.
 */
class SimClockSpeedTest {

    private static final long HOUR_MS = 3_600_000L;

    // ---------------------------------------------------------------- speed multiplies game pace

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void advanceCoversSpeedTimesAsMuchGameTime(int speed) {
        SimClock clock = new SimClock(3_600); // the sandbox default: 60 real minutes a day
        clock.setSpeedMultiplier(speed);

        clock.advance(150_000); // 150 real seconds = one game hour at x1

        assertEquals(speed * HOUR_MS, clock.nowSimMillis());
    }

    @Test
    void theBaseRateIsWhatASaveRecordsAndSpeedLeavesItAlone() {
        SimClock clock = new SimClock(3_600);
        clock.setSpeedMultiplier(4);
        assertEquals(3_600L, clock.realSecondsPerGameDay(), "realSecondsPerGameDay() stays the x1 base");
        assertEquals(4, clock.speedMultiplier());
    }

    @Test
    void changingSpeedMidRunKeepsGameTimeContinuous() {
        SimClock clock = new SimClock(3_600);
        clock.advance(150_000); // 1 game hour at x1
        clock.setSpeedMultiplier(4);
        clock.advance(150_000); // 4 game hours at x4
        clock.setSpeedMultiplier(1);
        clock.advance(150_000); // 1 game hour at x1

        assertEquals(6 * HOUR_MS, clock.nowSimMillis(), "1 + 4 + 1 game hours, no jump at either switch");
    }

    @Test
    void wallConversionsShrinkWithSpeed() {
        SimClock clock = new SimClock(3_600);
        assertEquals(150_000L, clock.simSecondsToWallMs(3_600), "one game hour is 150 real seconds at x1");

        clock.setSpeedMultiplier(4);

        assertEquals(37_500L, clock.simSecondsToWallMs(3_600), "and a quarter of that at x4");
        assertEquals(37.5, clock.realSecondsForSimMillis(HOUR_MS), 1e-9);
        assertEquals(HOUR_MS, clock.simMillisForRealSeconds(37.5));
    }

    @Test
    void conversionsRoundTripAtEverySpeed() {
        SimClock clock = new SimClock(1_800);
        for (int speed : new int[] {1, 2, 4}) {
            clock.setSpeedMultiplier(speed);
            assertEquals(30.0, clock.realSecondsForSimMillis(clock.simMillisForRealSeconds(30.0)), 1e-6,
                    "30 real seconds must mean the same game interval and back at x" + speed);
        }
    }

    @Test
    void aNonPositiveSpeedIsRejected() {
        SimClock clock = new SimClock(3_600);
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeedMultiplier(0));
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeedMultiplier(-2));
        assertEquals(1, clock.speedMultiplier());
    }

    @Test
    void restoreAdoptsTheSavedBaseRateButNotASpeed() {
        // Speed is a SETTING: the loader applies the saved Settings block, not the clock restore.
        SimClock clock = new SimClock(3_600);
        clock.setSpeedMultiplier(4);

        clock.restore(HOUR_MS, 900);

        assertEquals(900L, clock.realSecondsPerGameDay());
        assertEquals(4, clock.speedMultiplier(), "restore leaves the multiplier for the loader to set");
    }

    // ---------------------------------------------------------------- the timing epoch

    @Test
    void theTimingEpochMovesOnEveryDisturbanceOfTheSimWallRelation() {
        SimClock clock = new SimClock(3_600);
        long start = clock.timingEpoch();

        clock.advance(10_000);
        clock.resume(); // not paused: nothing happened
        clock.setSpeedMultiplier(1); // already x1
        clock.setRealSecondsPerGameDay(3_600); // already 3600
        assertEquals(start, clock.timingEpoch(), "plain running, and no-op setters, leave the epoch alone");

        clock.setSpeedMultiplier(2);
        long afterSpeed = clock.timingEpoch();
        assertNotEquals(start, afterSpeed, "a speed change");

        clock.setRealSecondsPerGameDay(1_800);
        long afterRate = clock.timingEpoch();
        assertNotEquals(afterSpeed, afterRate, "a day-length change");

        clock.pause();
        long afterPause = clock.timingEpoch();
        assertNotEquals(afterRate, afterPause, "a pause");
        clock.pause(); // idempotent
        assertEquals(afterPause, clock.timingEpoch(), "pausing a paused clock is not a second disturbance");

        clock.restore(0L, 3_600);
        assertNotEquals(afterPause, clock.timingEpoch(), "a restore");
    }

    // ---------------------------------------------------------------- conversation time (ADR-18)

    @Test
    void advanceFeedsTheConversationClockRealTimeUnscaledBySpeed() {
        SimClock clock = new SimClock(3_600);
        clock.conversationClock().open("nego-1", 120_000L);
        clock.setSpeedMultiplier(4);

        clock.advance(30_000);

        assertEquals(30_000L, clock.conversationClock().view("nego-1").orElseThrow().elapsedMillis(),
                "30 real seconds of patience are 30 seconds at any game speed");
        assertEquals(SimClock.simMillisDelta(120_000, 3_600), clock.nowSimMillis(),
                "while game time ran four times as far");
    }

    @Test
    void theDayLengthDoesNotChangePatienceEither() {
        SimClock fast = new SimClock(300);
        SimClock slow = new SimClock(7_200);
        fast.conversationClock().open("nego-1", 120_000L);
        slow.conversationClock().open("nego-1", 120_000L);

        fast.advance(45_000);
        slow.advance(45_000);

        assertEquals(75_000L, fast.conversationClock().view("nego-1").orElseThrow().remainingMillis());
        assertEquals(75_000L, slow.conversationClock().view("nego-1").orElseThrow().remainingMillis());
    }

    @Test
    void aPausedGameFreezesConversationTime() {
        SimClock clock = new SimClock(3_600);
        clock.conversationClock().open("nego-1", 120_000L);
        clock.advance(20_000);

        clock.pause();
        clock.advance(500_000); // the player is reading, thinking, typing

        assertEquals(100_000L, clock.conversationClock().view("nego-1").orElseThrow().remainingMillis(),
                "nothing runs down while the game is paused");

        clock.resume();
        clock.advance(10_000);
        assertEquals(90_000L, clock.conversationClock().view("nego-1").orElseThrow().remainingMillis());
    }

    @Test
    void aWorldSwapClearsEveryConversation() {
        // Open negotiations are never saved (task 22), so neither a restore nor a reset may leave
        // an old world's conversation running under an id the next world will reuse.
        SimClock clock = new SimClock(3_600);
        clock.conversationClock().open("nego-WALKIN-1", 120_000L);
        clock.restore(HOUR_MS, 3_600);
        assertTrue(clock.conversationClock().view("nego-WALKIN-1").isEmpty(), "restore");

        clock.conversationClock().open("nego-WALKIN-1", 120_000L);
        clock.reset();
        assertTrue(clock.conversationClock().view("nego-WALKIN-1").isEmpty(), "reset");
        assertFalse(clock.isPaused());
    }
}
