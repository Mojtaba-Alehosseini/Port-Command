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
 * the whole game</strong> (CLAUDE.md rule: SimClock is the only authoritative
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

    /** 100 ms per tick ≈ 28.8 sim-seconds at the default 300 s/game-day. */
    static final long DEFAULT_TICK_PERIOD_MS = 100L;

    private static final Logger log = LoggerFactory.getLogger(WallClockAdvancer.class);

    private final SimClock simClock;
    private final long tickPeriodMs;
    private ScheduledExecutorService executor;

    public WallClockAdvancer(SimClock simClock) {
        this(simClock, DEFAULT_TICK_PERIOD_MS);
    }

    /** Test seam: a shorter period keeps the advancer's own unit test fast. */
    WallClockAdvancer(SimClock simClock, long tickPeriodMs) {
        if (tickPeriodMs <= 0) {
            throw new IllegalArgumentException("tickPeriodMs must be > 0, got " + tickPeriodMs);
        }
        this.simClock = simClock;
        this.tickPeriodMs = tickPeriodMs;
    }

    /** Starts driving the clock. Idempotent (a second call while running is a no-op). */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
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

    private void tick() {
        try {
            simClock.advance(tickPeriodMs);
        } catch (RuntimeException e) {
            // scheduleAtFixedRate silently cancels the task on an uncaught throw — a frozen
            // game clock with no error would be the worst possible failure shape. advance()
            // is pure arithmetic and should never throw; log loudly if it somehow does.
            log.error("sim-clock advance failed — game time may stall", e);
        }
    }
}
