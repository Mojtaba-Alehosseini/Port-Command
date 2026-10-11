package it.unige.portcommand.behaviours;

import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;

/**
 * Sim-clock-aware analogue of JADE's {@link TickerBehaviour}: fires every
 * {@code tickSimMillis} <em>simulated</em> milliseconds, translated to a
 * wall-clock period via {@link SimClock#simSecondsToWallMs(long)} so a tick's
 * cadence scales with the sim/real mapping instead of hard-coding wall delays.
 * Subclasses implement {@link #onSimTick()}.
 *
 * <p>Reused by {@code ProcessCargoHandlingBehaviour} (task 06, 60_000 sim-ms) and
 * {@code TimeoutWithdrawalBehaviour} (task 07, 5_000 sim-ms). The live
 * per-sim-minute {@code SimClockTickEvent} publisher that uses this base is wired
 * in task 24 (the wall-clock tick driver), per the task-03 Option-B split.
 *
 * <p><b>S14: the period follows the pace.</b> The wall period used to be worked out once, at
 * construction. With a live speed control (and the day-length setting, which had the same gap
 * since task 21) a ticker armed at &times;1 would keep ticking four sim-intervals apart at
 * &times;4 — a sim-minute clock that reports every fourth minute, an end-of-day check four
 * minutes coarse. After every tick the period is re-derived and, only if it changed, the ticker
 * is re-armed with it; an unchanged pace never takes that branch. One tick at the old period can
 * still be pending when the pace changes — late by at most that one period; the re-armed
 * ticker's next tick is one new period after it (JADE's {@code reset} re-runs {@code onStart}
 * on the next scheduler pass, it does not cost a period). Eleven of the twelve subclasses
 * compare against sim time — a deadline, or the clock itself — so for them a late tick delays
 * an observation and loses nothing. {@code WeatherEvolutionBehaviour} is the exception: it takes
 * one Markov step per TICK, so the tick pending across a speed-up takes one weather step where
 * several were due, once per change. A subclass must not override {@code onStart}.
 */
public abstract class SimTickerBehaviour extends TickerBehaviour {

    private final transient SimClock simClock;
    private final long tickSimMillis;
    /** The wall period the ticker is currently armed with. */
    private long armedWallPeriodMs;

    protected SimTickerBehaviour(Agent a, SimClock simClock, long tickSimMillis) {
        super(a, wallPeriodMs(simClock, tickSimMillis));
        this.simClock = simClock;
        this.tickSimMillis = tickSimMillis;
        this.armedWallPeriodMs = wallPeriodMs(simClock, tickSimMillis);
    }

    /** Wall-clock period (ms) for a sim-ms tick interval; never less than 1 ms. */
    static long wallPeriodMs(SimClock simClock, long tickSimMillis) {
        return Math.max(1L, simClock.simSecondsToWallMs(tickSimMillis / 1000L));
    }

    @Override
    protected final void onTick() {
        onSimTick();
        retimeIfPaceChanged();
    }

    /** S14: see the class javadoc. Skipped once the subclass has removed itself in
     * {@link #onSimTick()} ({@code removeBehaviour} nulls {@code myAgent}) or stopped —
     * {@code reset} would otherwise bring a finished ticker back to life. */
    private void retimeIfPaceChanged() {
        if (myAgent == null || done()) {
            return;
        }
        long period = wallPeriodMs(simClock, tickSimMillis);
        if (period != armedWallPeriodMs) {
            armedWallPeriodMs = period;
            reset(period);
        }
    }

    /** Invoked once per simulated tick. */
    protected abstract void onSimTick();

    /** The shared clock, for subclasses that read {@code simClock().nowSimMillis()} in {@link #onSimTick()}. */
    protected SimClock simClock() {
        return simClock;
    }
}
