package it.unige.portcommand.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for {@link SimClock}. The clock is pure — wall-clock
 * elapsed time is supplied to {@code advance(...)} by the caller (the task-24
 * driver), never read from {@code System.currentTimeMillis()} — so every case is
 * exact-integer.
 */
class SimClockTest {

    private static final long DAY_MS = 86_400_000L;

    @ParameterizedTest
    @CsvSource({
            // realMillisElapsed, realSecondsPerGameDay, expectedSimMillisDelta
            "1000, 300, 288000",
            "400,  300, 115200",
            "100,  300, 28800",
            "1000, 600, 144000",
            "0,    300, 0",
    })
    void simMillisDeltaMatchesTable(long realMillis, long rate, long expected) {
        assertEquals(expected, SimClock.simMillisDelta(realMillis, rate));
    }

    @Test
    void advanceAccumulatesExactSimMillis() {
        SimClock clock = new SimClock(300);
        clock.advance(1000);
        assertEquals(288_000L, clock.nowSimMillis());
        clock.advance(1000);
        assertEquals(576_000L, clock.nowSimMillis());
    }

    @Test
    void simHourMinuteGameDayDerivedFromSimMillis() {
        SimClock clock = new SimClock(300);
        clock.advance(12_500); // 12_500 * 288 = 3_600_000 sim-ms = exactly 1 sim-hour
        assertEquals(3_600_000L, clock.nowSimMillis());
        assertEquals(1, clock.simHour());
        assertEquals(0, clock.simMinute());
        assertEquals(1, clock.gameDay());
    }

    @Test
    void pauseSuppressesAdvanceAndResumeDoesNotInflate() {
        SimClock clock = new SimClock(300);
        clock.pause();
        clock.advance(5000); // dropped while paused
        assertEquals(0L, clock.nowSimMillis());
        clock.resume();
        clock.advance(1000);
        assertEquals(288_000L, clock.nowSimMillis());
    }

    @Test
    void pauseAndResumeAreIdempotent() {
        SimClock clock = new SimClock(300);
        clock.pause();
        clock.pause();
        clock.resume();
        clock.resume(); // no-op when not paused
        clock.advance(1000);
        assertEquals(288_000L, clock.nowSimMillis());
    }

    @Test
    void advanceToNextDayJumpsToBoundaryFromMidday() {
        SimClock clock = new SimClock(300);
        clock.advance(150_000); // 150_000 * 288 = 43_200_000 sim-ms = noon of day 1
        assertEquals(DAY_MS / 2, clock.nowSimMillis());
        clock.advanceToNextDay();
        assertEquals(DAY_MS, clock.nowSimMillis());
        assertEquals(2, clock.gameDay());
    }

    @Test
    void isMidnightCrossedReturnsTrueExactlyOncePerDay() {
        SimClock clock = new SimClock(300);
        assertFalse(clock.isMidnightCrossed(), "day 1 start is not a crossing");
        clock.advanceToNextDay();
        assertTrue(clock.isMidnightCrossed(), "first observation after midnight");
        assertFalse(clock.isMidnightCrossed(), "second observation same day");
    }

    @Test
    void resetClearsTimeAndMidnightState() {
        SimClock clock = new SimClock(300);
        clock.advanceToNextDay();
        clock.isMidnightCrossed();
        clock.reset();
        assertEquals(0L, clock.nowSimMillis());
        assertEquals(1, clock.gameDay());
        assertFalse(clock.isMidnightCrossed());
    }

    @Test
    void simSecondsToWallMsIsInverseOfAdvanceRate() {
        assertEquals(208L, new SimClock(300).simSecondsToWallMs(60));
        assertEquals(416L, new SimClock(600).simSecondsToWallMs(60));
    }

    @Test
    void constructorRejectsNonPositiveRate() {
        assertThrows(IllegalArgumentException.class, () -> new SimClock(0));
        assertThrows(IllegalArgumentException.class, () -> new SimClock(-1));
    }

    @Test
    void concurrentAdvancesSumLikeSerial() throws Exception {
        SimClock clock = new SimClock(300);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        clock.advance(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "threads finished");
        } finally {
            pool.shutdownNow();
        }
        // 4 * delta(100,300) == delta(400,300)
        assertEquals(SimClock.simMillisDelta(400, 300), clock.nowSimMillis());
    }
}
