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
 */
public abstract class SimTickerBehaviour extends TickerBehaviour {

    private final transient SimClock simClock;

    protected SimTickerBehaviour(Agent a, SimClock simClock, long tickSimMillis) {
        super(a, wallPeriodMs(simClock, tickSimMillis));
        this.simClock = simClock;
    }

    /** Wall-clock period (ms) for a sim-ms tick interval; never less than 1 ms. */
    static long wallPeriodMs(SimClock simClock, long tickSimMillis) {
        return Math.max(1L, simClock.simSecondsToWallMs(tickSimMillis / 1000L));
    }

    @Override
    protected final void onTick() {
        onSimTick();
    }

    /** Invoked once per simulated tick. */
    protected abstract void onSimTick();

    /** The shared clock, for subclasses that read {@code simClock().nowSimMillis()} in {@link #onSimTick()}. */
    protected SimClock simClock() {
        return simClock;
    }
}
