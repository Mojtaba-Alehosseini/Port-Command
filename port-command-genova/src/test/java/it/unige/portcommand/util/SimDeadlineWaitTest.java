package it.unige.portcommand.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14: {@link SimDeadlineWait} — a wall-armed timer waiting for a SIM-time deadline. The test
 * plays the timer: it "sleeps" each delay the wait hands back by simply adding it up, and moves
 * the clock by hand, so nothing here touches a real timer.
 */
class SimDeadlineWaitTest {

    private static final long HOUR_MS = 3_600_000L;

    /** Sleeps every chunk without moving the clock; returns the total wall time waited. */
    private static long drain(SimDeadlineWait wait, long firstDelay) {
        long total = firstDelay;
        long delay = firstDelay;
        int guard = 0;
        while (delay > 0) {
            delay = wait.next();
            total += delay;
            assertTrue(++guard < 100_000, "the wait must end");
        }
        return total;
    }

    @Test
    void withAnUntouchedPaceTheChunksAddUpToTheOldSingleDelay() {
        // The behaviour-preservation pin: a clock nothing advances and nothing re-paces (most
        // agent ITs) must wait exactly simSecondsToWallMs(gap), as the one-shot waker did.
        SimClock clock = new SimClock(3_600);
        SimDeadlineWait wait = new SimDeadlineWait(clock);

        long first = wait.armUntil(2 * HOUR_MS); // a contracted vessel, two game hours out

        assertEquals(SimDeadlineWait.CHUNK_WALL_MS, first, "never one long sleep");
        assertEquals(clock.simSecondsToWallMs(7_200), drain(wait, first), "300 real seconds in all");
    }

    @Test
    void aDeadlineAlreadyDueIsOverAtOnceEvenWhilePaused() {
        // A walk-in's ETA is "now": spawned under a paused clock it must still arrive.
        SimClock clock = new SimClock(3_600);
        clock.advance(150_000);
        clock.pause();
        SimDeadlineWait wait = new SimDeadlineWait(clock);

        assertEquals(0L, wait.armUntil(HOUR_MS));
        assertEquals(0L, wait.next());
    }

    @Test
    void aShortWaitIsASingleChunk() {
        SimClock clock = new SimClock(3_600);
        SimDeadlineWait wait = new SimDeadlineWait(clock);

        long first = wait.armUntil(12_000L); // 12 game seconds = 500 real ms

        assertEquals(500L, first);
        assertEquals(0L, wait.next());
    }

    @Test
    void speedingUpShortensTheRestOfTheWait() {
        SimClock clock = new SimClock(3_600);
        SimDeadlineWait wait = new SimDeadlineWait(clock);
        long delay = wait.armUntil(2 * HOUR_MS); // 300 real seconds at x1

        // 100 real seconds pass at x1: 40 game minutes done, 80 to go.
        long waited = 0;
        while (waited < 100_000) {
            clock.advance(delay);
            waited += delay;
            delay = wait.next();
        }
        clock.setSpeedMultiplier(4);

        long rest = drain(wait, delay) - delay; // the chunk in hand was armed before the change
        assertEquals(clock.simSecondsToWallMs((2 * HOUR_MS - clock.nowSimMillis()) / 1000), rest,
                "the remaining 80 game minutes take 50 real seconds at x4, not 200");
    }

    @Test
    void slowingDownLengthensTheRestOfTheWait() {
        SimClock clock = new SimClock(3_600);
        clock.setSpeedMultiplier(4);
        SimDeadlineWait wait = new SimDeadlineWait(clock);
        long delay = wait.armUntil(2 * HOUR_MS); // 75 real seconds at x4

        clock.advance(delay); // one second at x4
        clock.setSpeedMultiplier(1);
        delay = wait.next();

        long remainingSimSeconds = (2 * HOUR_MS - clock.nowSimMillis() + 999) / 1000;
        assertEquals(clock.simSecondsToWallMs(remainingSimSeconds), drain(wait, delay),
                "the rest is re-derived at the slower pace instead of firing early");
    }

    @Test
    void aPauseHoldsTheWaitAndTheRestIsReDerivedOnResume() {
        SimClock clock = new SimClock(3_600);
        SimDeadlineWait wait = new SimDeadlineWait(clock);
        long delay = wait.armUntil(HOUR_MS); // 150 real seconds
        clock.advance(delay);
        delay = wait.next();

        clock.pause();
        for (int i = 0; i < 2_000; i++) { // a long pause: 500 real seconds, far past the old budget
            clock.advance(delay); // ignored — paused
            delay = wait.next();
            assertEquals(SimDeadlineWait.HOLD_WALL_MS, delay, "held, re-checked every quarter second");
        }
        clock.resume();

        assertEquals(clock.simSecondsToWallMs((HOUR_MS - clock.nowSimMillis() + 999) / 1000),
                drain(wait, wait.next()),
                "after the pause the full remaining game time is still to be waited");
    }

    @Test
    void aDayLengthChangeIsFollowedToo() {
        SimClock clock = new SimClock(3_600);
        SimDeadlineWait wait = new SimDeadlineWait(clock);
        wait.armUntil(HOUR_MS); // 150 real seconds at 3600 s/day

        clock.setRealSecondsPerGameDay(300); // the Settings slider: a 5-minute day

        assertEquals(clock.simSecondsToWallMs(3_600), drain(wait, wait.next()), "12.5 real seconds now");
    }

    @Test
    void reArmingStartsACleanWait() {
        SimClock clock = new SimClock(3_600);
        SimDeadlineWait wait = new SimDeadlineWait(clock);
        drain(wait, wait.armUntil(HOUR_MS));
        clock.advance(150_000);

        long first = wait.armUntil(clock.nowSimMillis() + HOUR_MS); // the next Poisson gap

        assertEquals(150_000L, drain(wait, first));
    }

    @Test
    void aWaitArmedWhilePausedStillWaitsTheWholeDistanceAfterResume() {
        // Every load and scenario start arms the Poisson gap under the loader's pause. resume()
        // does not move the timing epoch, so without help nothing would tell next() that the
        // first chunk was slept through a standing clock (adversarial review, S14).
        SimClock clock = new SimClock(3_600);
        clock.pause();
        SimDeadlineWait wait = new SimDeadlineWait(clock);

        long delay = wait.armUntil(HOUR_MS); // 150 real seconds of game time away
        assertTrue(delay > 0, "a future deadline is never due at once");
        delay = wait.next();
        assertEquals(SimDeadlineWait.HOLD_WALL_MS, delay, "held while paused");

        clock.resume();

        assertEquals(clock.simSecondsToWallMs(3_600), drain(wait, wait.next()),
                "no game time ran during the pause: the whole hour is still to be waited");
    }

    @Test
    void pausingSetsTheFlagBeforeItMovesTheEpoch() {
        // A waiter reads the epoch and then isPaused(). If pause() bumped the epoch first, a
        // waiter landing between the two statements would record the NEW epoch with the flag
        // still clear and never re-derive. Observable single-threaded as: by the time the epoch
        // has moved, the clock already reports paused.
        SimClock clock = new SimClock(3_600);
        long before = clock.timingEpoch();
        clock.pause();
        assertTrue(clock.isPaused());
        assertEquals(before + 1, clock.timingEpoch());
    }
}
