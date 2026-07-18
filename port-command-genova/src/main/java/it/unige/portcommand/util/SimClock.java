package it.unige.portcommand.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulated game time, decoupled from the wall clock.
 *
 * <p>The clock is <strong>pure</strong>: it never reads
 * {@code System.currentTimeMillis()}. The caller (the wall-clock tick driver,
 * wired in task 24) supplies elapsed real milliseconds to {@link #advance(long)};
 * that keeps the clock fully deterministic for tests.
 *
 * <p>Mapping is governed by {@code realSecondsPerGameDay} (default 300 — five real
 * minutes per simulated 24&nbsp;h). Injected via constructor; never a global static
 * singleton, so tests can use their own deterministic instance.
 *
 * <p>Thread-safe: the sim-time counter is an {@link AtomicLong} and midnight
 * detection uses a CAS, so concurrent agent advances are safe.
 */
public final class SimClock {

    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long SECONDS_PER_DAY = 86_400L;

    /**
     * Volatile, not final, since task 22: {@link #restore} may adopt a save file's rate
     * (the sim↔wall mapping is sim state — a save made at 300&nbsp;s/day must not speed up
     * to a --day-length 75 relaunch). Written only at construction and in {@code restore}.
     */
    private volatile long realSecondsPerGameDay;
    private final AtomicLong simMillisSinceStart = new AtomicLong(0);
    private final AtomicLong lastObservedDay = new AtomicLong(1);
    private volatile boolean paused;

    public SimClock(long realSecondsPerGameDay) {
        if (realSecondsPerGameDay <= 0) {
            throw new IllegalArgumentException(
                    "realSecondsPerGameDay must be > 0, got " + realSecondsPerGameDay);
        }
        this.realSecondsPerGameDay = realSecondsPerGameDay;
    }

    /**
     * Sim-millisecond delta for an elapsed real interval, given the mapping.
     * {@code realMs / 1000 / realSecondsPerGameDay} game-days, times 86_400_000
     * ms/day. Static + side-effect-free so the conversion can be unit-tested in
     * isolation.
     */
    public static long simMillisDelta(long realMillisElapsed, long realSecondsPerGameDay) {
        return realMillisElapsed * MILLIS_PER_DAY / (realSecondsPerGameDay * 1_000L);
    }

    /** Advances sim time by the sim-equivalent of {@code realMillisElapsed}; no-op while paused. */
    public void advance(long realMillisElapsed) {
        if (paused) {
            return;
        }
        simMillisSinceStart.addAndGet(simMillisDelta(realMillisElapsed, realSecondsPerGameDay));
    }

    /** Stops {@link #advance(long)} from accumulating. Idempotent. */
    public void pause() {
        paused = true;
    }

    /** Re-enables {@link #advance(long)}. Idempotent (no-op if not paused). */
    public void resume() {
        paused = false;
    }

    /** Whether {@link #advance(long)} is currently a no-op. */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Jumps sim time to the next midnight boundary in one atomic step. Used by the
     * {@code DayRolloverCoordinator} (task 24). A subsequent {@link #isMidnightCrossed()}
     * then reports the crossing.
     */
    public void advanceToNextDay() {
        simMillisSinceStart.updateAndGet(cur -> ((cur / MILLIS_PER_DAY) + 1) * MILLIS_PER_DAY);
    }

    /** Resets to the start of day 1, unpaused. */
    public void reset() {
        simMillisSinceStart.set(0);
        lastObservedDay.set(1);
        paused = false;
    }

    /**
     * Adopts a persisted clock state (task 22 load). Sets sim time and the mapping rate, and
     * — load-bearing — seeds {@code lastObservedDay} to the restored instant's own day so
     * {@link #isMidnightCrossed()} does not fire a phantom crossing for days that settled
     * before the save. Leaves the clock paused; the load orchestrator resumes it once the
     * rebuilt world is ready.
     */
    public void restore(long simMillis, long realSecondsPerGameDay) {
        if (simMillis < 0) {
            throw new IllegalArgumentException("simMillis must be >= 0, got " + simMillis);
        }
        if (realSecondsPerGameDay <= 0) {
            throw new IllegalArgumentException(
                    "realSecondsPerGameDay must be > 0, got " + realSecondsPerGameDay);
        }
        paused = true;
        this.realSecondsPerGameDay = realSecondsPerGameDay;
        simMillisSinceStart.set(simMillis);
        lastObservedDay.set(gameDayOf(simMillis));
    }

    public long nowSimMillis() {
        return simMillisSinceStart.get();
    }

    public int simHour() {
        return (int) ((nowSimMillis() / MILLIS_PER_HOUR) % 24);
    }

    public int simMinute() {
        return (int) ((nowSimMillis() / MILLIS_PER_MINUTE) % 60);
    }

    public int gameDay() {
        return gameDayOf(nowSimMillis());
    }

    /**
     * The 1-based game day a sim-millis instant falls in — the pure, static form of
     * {@link #gameDay()}, which delegates here.
     *
     * <p>Added by task 20 so the financial ledgers can bucket a recorded
     * {@code IncomeEvent}/{@code ExpenseEvent} by day from its own {@code simTime} stamp
     * instead of carrying a separate day field. Deriving it means an event's day can never
     * disagree with its timestamp, and a day aggregation stays reproducible from history
     * alone — no clock instance, no read of live mutable state.
     */
    public static int gameDayOf(long simMillis) {
        return (int) (simMillis / MILLIS_PER_DAY) + 1;
    }

    /**
     * Returns {@code true} exactly once per simulated midnight: the first call
     * after the game day advances wins the CAS; later calls in the same day return
     * {@code false}.
     */
    public boolean isMidnightCrossed() {
        long current = gameDay();
        long last = lastObservedDay.get();
        return current > last && lastObservedDay.compareAndSet(last, current);
    }

    /**
     * Converts a simulated-second interval back to wall-clock milliseconds at the
     * current mapping — the inverse of {@link #advance(long)}. Used by
     * {@code SimTickerBehaviour} and {@code WakerBehaviour} deadlines so timers
     * scale with {@code realSecondsPerGameDay} instead of hard-coding wall delays.
     */
    public long simSecondsToWallMs(long simSeconds) {
        // Inverse of simMillisDelta: realMs = simSeconds * rate * 1000 / secondsPerDay.
        return simSeconds * realSecondsPerGameDay * 1_000L / SECONDS_PER_DAY;
    }

    public long realSecondsPerGameDay() {
        return realSecondsPerGameDay;
    }

    /**
     * Changes ONLY the sim↔wall mapping rate, live, without touching accumulated sim time or
     * the pause/day state (task 21's Settings day-length slider). Distinct from {@link #restore}:
     * that adopts a whole persisted clock (and pauses); this is the "speed dial" — the already
     * accumulated {@code simMillis} is left exactly where it is, so game time is continuous and
     * only FUTURE {@link #advance(long)} deltas map at the new rate. Thread-safe: the field is
     * {@code volatile} and every reader ({@code WallClockAdvancer}, the countdown) re-reads it.
     */
    public void setRealSecondsPerGameDay(long realSecondsPerGameDay) {
        if (realSecondsPerGameDay <= 0) {
            throw new IllegalArgumentException(
                    "realSecondsPerGameDay must be > 0, got " + realSecondsPerGameDay);
        }
        this.realSecondsPerGameDay = realSecondsPerGameDay;
    }
}
