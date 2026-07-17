package it.unige.portcommand.lifecycle;

import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one test class that legitimately touches wall time: the advancer IS the
 * wall→sim bridge, so observing it tick requires waiting on a real scheduler.
 * Both waits are bounded polls with explicit deadlines — the documented
 * exception pattern (INVARIANTS, task 11: no Awaitility dependency exists;
 * bounded {@code Thread.sleep} polls inside an explicit-deadline loop are the
 * sanctioned substitute). Everything else in the task-24 suite drives
 * {@code SimClock.advance} directly.
 */
class WallClockAdvancerTest {

    @Test
    void advancesTheClockWhileRunning() throws InterruptedException {
        SimClock clock = new SimClock(300);
        WallClockAdvancer advancer = new WallClockAdvancer(clock, 5L);
        try {
            advancer.start();
            long deadline = System.currentTimeMillis() + 2_000;
            while (clock.nowSimMillis() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5); // bounded poll (see class javadoc)
            }
            assertTrue(clock.nowSimMillis() > 0, "advancer never advanced the clock within 2 s");
        } finally {
            advancer.stop();
        }
    }

    @Test
    void stopFreezesTheClock() throws InterruptedException {
        SimClock clock = new SimClock(300);
        WallClockAdvancer advancer = new WallClockAdvancer(clock, 5L);
        advancer.start();
        long deadline = System.currentTimeMillis() + 2_000;
        while (clock.nowSimMillis() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5); // bounded poll (see class javadoc)
        }
        advancer.stop();

        long frozenAt = clock.nowSimMillis();
        // A short bounded observation window: any still-live scheduler thread would tick
        // several times in 50 ms at a 5 ms period.
        for (int i = 0; i < 10; i++) {
            Thread.sleep(5);
            assertEquals(frozenAt, clock.nowSimMillis(), "clock advanced after stop()");
        }
        assertFalse(advancer.isRunning());
    }

    @Test
    void pausedClockMakesTicksHarmless() throws InterruptedException {
        SimClock clock = new SimClock(300);
        clock.pause();
        WallClockAdvancer advancer = new WallClockAdvancer(clock, 5L);
        try {
            advancer.start();
            for (int i = 0; i < 10; i++) {
                Thread.sleep(5); // bounded observation window (see class javadoc)
                assertEquals(0L, clock.nowSimMillis(), "a paused clock must ignore advancer ticks");
            }
        } finally {
            advancer.stop();
        }
    }

    @Test
    void startAndStopAreIdempotent() {
        WallClockAdvancer advancer = new WallClockAdvancer(new SimClock(300), 1_000L);
        advancer.start();
        advancer.start();
        assertTrue(advancer.isRunning());
        advancer.stop();
        advancer.stop();
        assertFalse(advancer.isRunning());
    }

    @Test
    void rejectsANonPositivePeriod() {
        assertThrows(IllegalArgumentException.class, () -> new WallClockAdvancer(new SimClock(300), 0L));
    }
}
