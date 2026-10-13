package it.unige.portcommand.behaviours.coordination;

import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.WeatherSnapshot;
import it.unige.portcommand.agents.WeatherStepper;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;

/**
 * Keeps the weather in step with the game clock: once a sim-minute it tells the agent's
 * {@link WeatherStepper} what time it is, writes the shared {@link AtomicReference} that
 * {@code PeriodicWeatherBroadcastBehaviour} reads if a step took effect, and has the new reading
 * broadcast at once.
 *
 * <p><b>S16 (2026-10-13, ADR-20, decision B6): the tick is no longer the step.</b> This used to
 * be a ticker armed with the wall length of sixty sim-minutes that took one Markov step per
 * TICK. A ticker's period is only re-derived after its next tick, so a speed change lost steps
 * (&times;1&rarr;&times;4: one step where four were due) or added one (&times;4&rarr;&times;1:
 * a step after fifteen game minutes); a tick that fell into a pause was dropped; after a load
 * the first step came "one period after boot", wherever that landed. The weather of a seed
 * depended on how the game was played. Now the weather steps on the full game hours of the SIM
 * clock, one step per hour boundary, and this behaviour is only the short tick that notices a
 * boundary has passed — late by at most a sim-minute, which costs nothing because the step is
 * stamped with its boundary and draws the same values whenever it is noticed. Everything a
 * step is — the Markov draw, the wind drift, the suspension inside a scenario's scripted
 * weather, the legacy init-args overrides — lives in the stepper, which has no JADE in it and
 * is unit-tested without a container.
 *
 * <p><b>A pause neither loses nor adds a step.</b> A paused clock does not move, so no boundary
 * is crossed; the explicit gate below only covers the pause that lands between a boundary and
 * the tick that would have noticed it. That step is then taken on the first tick after the
 * resume — while the clock runs, so the broadcast that follows it is not swallowed by the
 * broadcast's own pause gate.
 *
 * <p><b>The step is broadcast at once.</b> The periodic broadcast is fifteen sim-minutes apart;
 * a step that makes the weather unsafe used to reach the HarbourMaster up to that much later,
 * during which it kept granting movements under the old reading. The callback runs one normal
 * broadcast cycle on this same agent thread ({@code WeatherAgent.broadcastWeatherNow()}, the
 * route a scripted weather change has used since task 26).
 *
 * <p>The wind still drifts by a bounded amount per step instead of re-rolling across the whole
 * band (the task-19 fix for the threshold flapping) — see {@code WeatherStepper}.
 */
public final class WeatherEvolutionBehaviour extends SimTickerBehaviour {

    /** How often the clock is looked at — NOT how often the weather steps (that is hourly). */
    private static final long TICK_SIM_MILLIS = 60_000L;

    private final AtomicReference<WeatherSnapshot> current;
    private final WeatherStepper stepper;
    private final Runnable onStep;

    /**
     * @param onStep run on the agent thread after a step has been written to {@code current};
     *               the weather agent passes its immediate broadcast
     */
    public WeatherEvolutionBehaviour(Agent agent, SimClock simClock, AtomicReference<WeatherSnapshot> current,
                                     WeatherStepper stepper, Runnable onStep) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.current = current;
        this.stepper = stepper;
        this.onStep = onStep;
    }

    @Override
    protected void onSimTick() {
        if (simClock().isPaused()) {
            return; // pause gate (task 24): a boundary reached just before a pause is stepped after it
        }
        WeatherSnapshot before = current.get();
        if (before == null) {
            return;
        }
        WeatherSnapshot after = stepper.advanceTo(simClock().nowSimMillis(), before);
        // Identity, not equals: the stepper hands back the SAME object when no step took effect.
        // compareAndSet, not set: a Debug-menu or scripted override is written into this cell
        // from another thread (DebugWeatherOverrideBehaviour, CALLER_THREAD). If one landed
        // while the step was being worked out it is the newer instruction and stays; the step
        // computed from the reading it replaced is dropped.
        if (after != before && current.compareAndSet(before, after)) {
            onStep.run();
        }
    }
}
