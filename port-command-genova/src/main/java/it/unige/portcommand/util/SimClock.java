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
 * <p>Mapping is governed by {@code realSecondsPerGameDay} — the x1 BASE pace (sandbox default
 * 3600, one real hour per simulated 24&nbsp;h, since S14; scenarios pin their own) — times the
 * live {@link #speedMultiplier() speed multiplier} (x1/x2/x4, S14). Injected via constructor;
 * never a global static singleton, so tests can use their own deterministic instance.
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
    /** S14: the player's speed control. Multiplies the pace inside every sim<->wall conversion
     * below; {@link #realSecondsPerGameDay()} stays the x1 base (it is what a save records). */
    private volatile int speedMultiplier = 1;
    /** S14: bumped whenever the sim<->wall relation is disturbed — a pause, a pace change, a
     * restore. A timer armed ONCE in wall time (a JADE waker) compares the value it armed under
     * with the value at wake to know whether to re-derive its remaining wait from sim time. */
    private final AtomicLong timingEpoch = new AtomicLong(0);
    /** S14 (ADR-18): negotiation patience runs on REAL time, not on this clock's game time. It
     * lives here so real milliseconds enter the game in one place — see {@link #advance}. */
    private final ConversationClock conversationClock = new ConversationClock();

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

    /**
     * The inverse of {@link #simMillisDelta}: how many REAL seconds a sim-millisecond interval
     * corresponds to under this clock's current mapping.
     *
     * <p>Single-sourced here (2026-08-10, audit P1-9) for the negotiation tab's "~Ns remaining"
     * countdown and the HarbourMaster's {@code WalkInDialogueSnapshot.timeUsedSec}, which had to
     * agree exactly. S14 (ADR-18) retired both uses — negotiation time is no longer derived from
     * game time at all, it is read off the {@link #conversationClock()} — so today this pair of
     * conversions serves only what really is "real seconds of RUNNING game time": the chat
     * panel's closed-tab dwell (through {@link #simMillisForRealSeconds}). The speed multiplier
     * is folded in, so 30 real seconds are 30 real seconds at ×1 and at ×4.
     */
    public double realSecondsForSimMillis(long simMillisElapsed) {
        return simMillisElapsed * (double) realSecondsPerGameDay / MILLIS_PER_DAY / speedMultiplier;
    }

    /** The reverse of {@link #realSecondsForSimMillis}, for callers that need to reconstruct a
     * dialogue's sim-time origin from an elapsed real-seconds figure. */
    public long simMillisForRealSeconds(double realSeconds) {
        return Math.round(realSeconds * speedMultiplier * MILLIS_PER_DAY / (double) realSecondsPerGameDay);
    }

    /**
     * Advances sim time by the sim-equivalent of {@code realMillisElapsed}; no-op while paused.
     * The same real milliseconds, unscaled, also go to the {@link #conversationClock()} — so a
     * paused game freezes negotiation patience, and the speed multiplier never touches it.
     */
    public void advance(long realMillisElapsed) {
        if (paused) {
            return;
        }
        conversationClock.advance(realMillisElapsed);
        // Speed scales the ELAPSED time, not the base rate: dividing the base would truncate
        // (75 s/day at x2) and the static conversion above keeps its two-argument contract.
        simMillisSinceStart.addAndGet(simMillisDelta(realMillisElapsed * speedMultiplier, realSecondsPerGameDay));
    }

    /** Stops {@link #advance(long)} from accumulating. Idempotent. */
    public void pause() {
        // The flag BEFORE the epoch: a waiter that reads the epoch and then isPaused() (see
        // SimDeadlineWait.armUntil) must never see the bumped epoch with the flag still clear.
        boolean wasPaused = paused;
        paused = true;
        if (!wasPaused) {
            timingEpoch.incrementAndGet();
        }
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
        conversationClock.reset();
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
        timingEpoch.incrementAndGet(); // speed is NOT touched here: it is a setting, applied by the loader
        conversationClock.reset(); // a restore is a world swap, and open negotiations are never saved
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
    /**
     * Whether the clock is past a midnight that {@link #isMidnightCrossed()} has not reported yet
     * - the game day has changed and the day before it is still to be settled. Midnight is
     * detected by a ticker, up to a game minute late; this is that minute. Read-only: it does not
     * consume the crossing.
     */
    public boolean midnightPending() {
        return gameDayOf(nowSimMillis()) > lastObservedDay.get();
    }

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
        // Inverse of simMillisDelta: realMs = simSeconds * rate * 1000 / (secondsPerDay * speed).
        return simSeconds * realSecondsPerGameDay * 1_000L / (SECONDS_PER_DAY * speedMultiplier);
    }

    /** The x1 BASE pace: real seconds per game day before the speed multiplier. */
    public long realSecondsPerGameDay() {
        return realSecondsPerGameDay;
    }

    /** S14: the player's speed control (1, 2 or 4 in the shipped UI; any positive value here). */
    public int speedMultiplier() {
        return speedMultiplier;
    }

    /**
     * S14: changes ONLY how fast game time runs, live — accumulated sim time, the base rate and
     * the pause state are untouched, exactly like {@link #setRealSecondsPerGameDay}. Negotiation
     * patience is NOT game time (ADR-18): the conversation clock never sees this.
     */
    public void setSpeedMultiplier(int speedMultiplier) {
        if (speedMultiplier <= 0) {
            throw new IllegalArgumentException("speedMultiplier must be > 0, got " + speedMultiplier);
        }
        if (this.speedMultiplier != speedMultiplier) {
            this.speedMultiplier = speedMultiplier;
            timingEpoch.incrementAndGet();
        }
    }

    /** S14: see the field — changes on a pause, a pace change or a restore, never otherwise. */
    public long timingEpoch() {
        return timingEpoch.get();
    }

    /** S14 (ADR-18): the real-time clock walk-in patience runs on; fed by {@link #advance}. */
    public ConversationClock conversationClock() {
        return conversationClock;
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
        if (this.realSecondsPerGameDay != realSecondsPerGameDay) {
            this.realSecondsPerGameDay = realSecondsPerGameDay;
            timingEpoch.incrementAndGet();
        }
    }
}
