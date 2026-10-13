package it.unige.portcommand.lifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import it.unige.portcommand.util.SimClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live wall→sim clock driver — the task-03 "Option B" advancer, landed by
 * task 24. This class is <strong>the single sanctioned wall-clock consumer in
 * the whole game</strong> (design rule: SimClock is the only authoritative
 * time source; wall-clock is allowed only for log timestamps and for the
 * SimClock's own tick driver).
 *
 * <p>Mechanism: a single-thread daemon {@link ScheduledExecutorService} at a
 * fixed rate calls {@code simClock.advance(TICK_PERIOD_MS)} once per tick.
 * The scheduler's cadence <em>is</em> the wall-time source — the task never
 * reads {@code System.currentTimeMillis()}; it credits exactly one period per
 * firing, and {@code scheduleAtFixedRate} makes up missed firings, so sim time
 * tracks wall time in the long run without accumulating drift from per-tick
 * jitter (planning/24 "Risks": count ticks, don't measure deltas).
 *
 * <p>Pause needs no cooperation here: {@link SimClock#pause()} already makes
 * {@code advance()} a no-op, so a paused game just burns empty 100&nbsp;ms
 * ticks. Determinism: production is the only caller of {@link #start()};
 * integration tests never run the advancer — they drive
 * {@code simClock.advance(...)} directly (the established task-06..20 IT
 * pattern), which is what keeps seeded runs replayable.
 */
public final class WallClockAdvancer {

    /** 100 ms per tick ≈ 2.4 sim-seconds at the default 3600 s/game-day and ×1 (28.8 at the
     * pre-S14 300 s default). The same 100 ms are also the conversation clock's resolution:
     * {@code SimClock.advance} forwards them to it unscaled (ADR-18). */
    static final long DEFAULT_TICK_PERIOD_MS = 100L;

    /**
     * S17b (decision T4): the longest gap between two ticks that is still credited as game time.
     * A longer one is a stall - a laptop that slept, a debugger, a long GC pause - and is worth
     * one ordinary tick, not the time the machine was away.
     */
    static final long MAX_CREDITED_GAP_MS = 1_000L;

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final Logger log = LoggerFactory.getLogger(WallClockAdvancer.class);

    private final SimClock simClock;
    private final long tickPeriodMs;
    /** The wall clock the ticks are measured against ({@code System::nanoTime}; a test's own). */
    private final java.util.function.LongSupplier nanoTime;
    private ScheduledExecutorService executor;
    /** The wall time of the previous tick; meaningless until {@link #ticked}. Tick thread only
     * (and {@link #start()}, before the first tick is scheduled). */
    private long lastTickNanos;
    private boolean ticked;
    /** Elapsed time not yet credited: what is left under one millisecond. */
    private long carryNanos;

    public WallClockAdvancer(SimClock simClock) {
        this(simClock, DEFAULT_TICK_PERIOD_MS);
    }

    /** Test seam: a shorter period keeps the advancer's own unit test fast. */
    WallClockAdvancer(SimClock simClock, long tickPeriodMs) {
        this(simClock, tickPeriodMs, System::nanoTime);
    }

    /** Test seam: the wall clock is the caller's, so a stall can be staged without sleeping. */
    WallClockAdvancer(SimClock simClock, long tickPeriodMs, java.util.function.LongSupplier nanoTime) {
        if (tickPeriodMs <= 0) {
            throw new IllegalArgumentException("tickPeriodMs must be > 0, got " + tickPeriodMs);
        }
        this.simClock = simClock;
        this.tickPeriodMs = tickPeriodMs;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Starts driving the clock. Idempotent (a second call while running is a no-op). */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        ticked = false;
        carryNanos = 0L;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-clock-advancer");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, tickPeriodMs, tickPeriodMs, TimeUnit.MILLISECONDS);
        log.info("wall-clock advancer started ({} ms/tick, {} real-s per game day)",
                tickPeriodMs, simClock.realSecondsPerGameDay());
    }

    /** Stops driving the clock. Idempotent. */
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor = null;
        log.info("wall-clock advancer stopped");
    }

    public synchronized boolean isRunning() {
        return executor != null;
    }

    /** One scheduler firing. Package-private so the advancer's test can fire it by hand. */
    void tick() {
        try {
            // S17b (T4): a tick is worth the wall time that really passed since the one before -
            // not a fixed period. The scheduler runs at a fixed RATE: after a stall it fires every
            // tick it missed, back to back, and at a fixed 100 ms each that was the whole stall
            // paid back in a burst - ten minutes of game time in a fraction of a second, and every
            // waiting vessel timed out at once. Measured, the first tick after a stall sees one long
            // gap (credited as a single period) and the burst behind it sees gaps of microseconds.
            // Ordinary lateness is still credited in full, so game time tracks the wall clock on a
            // busy machine exactly as the fixed-rate catch-up did.
            long now = nanoTime.getAsLong();
            long elapsedNanos = ticked ? now - lastTickNanos : tickPeriodMs * NANOS_PER_MILLI;
            lastTickNanos = now;
            ticked = true;
            if (elapsedNanos > MAX_CREDITED_GAP_MS * NANOS_PER_MILLI) {
                log.warn("the game clock was not driven for {} ms (the machine slept or stalled) - "
                        + "that gap is not game time", elapsedNanos / NANOS_PER_MILLI);
                elapsedNanos = tickPeriodMs * NANOS_PER_MILLI;
            }
            carryNanos += Math.max(0L, elapsedNanos);
            long millis = carryNanos / NANOS_PER_MILLI;
            carryNanos -= millis * NANOS_PER_MILLI;
            if (millis > 0) {
                simClock.advance(millis);
            }
        } catch (RuntimeException e) {
            // scheduleAtFixedRate silently cancels the task on an uncaught throw — a frozen
            // game clock with no error would be the worst possible failure shape. advance()
            // is pure arithmetic and should never throw; log loudly if it somehow does.
            log.error("sim-clock advance failed — game time may stall", e);
        }
    }
}
