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

    /**
     * S17b, decision T4 — born red. The scheduler runs at a fixed RATE, so after a stall it fires
     * every tick it missed, back to back. Each tick used to be worth its full period whenever it
     * came: a laptop that slept for ten minutes woke up to ten minutes of game time delivered in
     * a fraction of a second, and every waiting vessel timed out at once (patience runs on these
     * same ticks, ADR-18).
     */
    @Test
    void aStallIsNotPaidBackAsABurstOfGameTime() {
        SimClock clock = new SimClock(86_400); // 1 real second = 1 game second
        java.util.concurrent.atomic.AtomicLong wall = new java.util.concurrent.atomic.AtomicLong(5_000_000_000L);
        WallClockAdvancer advancer = new WallClockAdvancer(clock, 100L, wall::get);
        for (int i = 0; i < 10; i++) {
            wall.addAndGet(100_000_000L);
            advancer.tick();
        }
        long before = clock.nowSimMillis();
        assertEquals(1_000L, before, "fixture: ten ordinary ticks are one second");

        wall.addAndGet(600_000_000_000L);       // the machine sleeps for ten minutes...
        for (int i = 0; i < 6_000; i++) {       // ...and the scheduler fires the 6000 ticks it missed
            advancer.tick();
            wall.addAndGet(20_000L);            // 20 microseconds apart
        }

        long credited = clock.nowSimMillis() - before;
        assertTrue(credited <= 1_000L,
                "ten minutes asleep must not become ten minutes of game time - credited " + credited + " ms");
    }

    /** The other half: ordinary lateness is NOT lost. A tick that comes 250 ms after the last is
     * worth 250 ms, so game time still tracks the wall clock on a busy machine. */
    @Test
    void aLateTickIsWorthTheTimeThatReallyPassed() {
        SimClock clock = new SimClock(86_400);
        java.util.concurrent.atomic.AtomicLong wall = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        WallClockAdvancer advancer = new WallClockAdvancer(clock, 100L, wall::get);
        advancer.tick(); // the first tick is worth one period
        long[] gapsMs = {100, 250, 30, 20, 700, 100};

        long total = 100;
        for (long gap : gapsMs) {
            wall.addAndGet(gap * 1_000_000L);
            advancer.tick();
            total += gap;
        }

        assertEquals(total, clock.nowSimMillis(), "every millisecond that passed, and no more");
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
