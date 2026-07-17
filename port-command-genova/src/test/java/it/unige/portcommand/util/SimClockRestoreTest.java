package it.unige.portcommand.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 22: {@link SimClock#restore(long, long)} semantics. */
class SimClockRestoreTest {

    @Test
    void restoreAdoptsInstantAndRateAndStaysPausedUntilResumed() {
        SimClock clock = new SimClock(300L);
        clock.restore(2 * 86_400_000L + 3_600_000L, 75L); // day 3, 01:00, saved at 75 s/day
        assertTrue(clock.isPaused(), "restored clock waits for the loader's resume");
        assertEquals(3, clock.gameDay());
        assertEquals(1, clock.simHour());
        assertEquals(75L, clock.realSecondsPerGameDay(), "the save's rate wins over the launch rate");

        clock.resume();
        clock.advance(75_000L); // one full game day at the RESTORED rate
        assertEquals(4, clock.gameDay());
    }

    @Test
    void restoreNeverFiresAPhantomMidnightForAlreadySettledDays() {
        SimClock clock = new SimClock(300L);
        clock.restore(2 * 86_400_000L + 3_600_000L, 300L); // day 3 mid-day
        assertFalse(clock.isMidnightCrossed(),
                "days 1-2 settled before the save — the restored clock must not re-report them");

        clock.resume();
        clock.advance(clock.simSecondsToWallMs(23 * 3_600L)); // cross into day 4
        assertTrue(clock.isMidnightCrossed(), "the NEXT real midnight still fires exactly once");
        assertFalse(clock.isMidnightCrossed());
    }
}
