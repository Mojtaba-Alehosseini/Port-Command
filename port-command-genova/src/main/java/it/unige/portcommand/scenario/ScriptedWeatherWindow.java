package it.unige.portcommand.scenario;

import java.util.List;
import java.util.Optional;

import it.unige.portcommand.scenario.events.ScriptedEvent;
import it.unige.portcommand.scenario.events.WeatherChangeScriptedEvent;

/**
 * The stretch of game time in which a scenario's own weather is in force and the random weather
 * steps must leave it alone (S16, 2026-10-13, ADR-20, decision B5).
 *
 * <p>Before S16 nothing connected the two: the Markov step simply overwrote whatever the script
 * had set. In the storm scenario the first random step after the scripted clear is stormy for the
 * shipped seed — a second, three-hour storm twenty-seven game minutes after the "Storm clearing"
 * banner — and a step landing inside the scripted window could rewrite the scripted reading.
 *
 * <p>The window runs from the scenario's FIRST scripted weather change to its LAST one plus
 * {@link #GRACE_SIM_MILLIS}. It is a pure function of the scenario file (absolute sim time: the
 * scenario's start plus the events' offsets), so it is the same on a fresh start and on every
 * load of a save of that scenario, and nothing about it needs saving. The weather agent receives
 * it at spawn (found by type among its arguments); free play and scenarios that script no
 * weather have none.
 *
 * @param fromSimMillis  the first scripted weather change, absolute sim millis
 * @param untilSimMillis the last scripted weather change plus the grace period, absolute sim
 *                       millis; the first random step that may take effect again is the first
 *                       one strictly after this instant
 */
public record ScriptedWeatherWindow(long fromSimMillis, long untilSimMillis) {

    /** One game hour after the last scripted weather change. */
    public static final long GRACE_SIM_MILLIS = 3_600_000L;

    /** The window of {@code scenario}, or empty for free play ({@code null}) or no scripted weather. */
    public static Optional<ScriptedWeatherWindow> of(Scenario scenario) {
        if (scenario == null) {
            return Optional.empty();
        }
        List<ScriptedEvent> events = scenario.events();
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (ScriptedEvent event : events) {
            if (event instanceof WeatherChangeScriptedEvent) {
                first = Math.min(first, event.simTimeSeconds());
                last = Math.max(last, event.simTimeSeconds());
            }
        }
        if (first == Long.MAX_VALUE) {
            return Optional.empty();
        }
        long start = scenario.initialState().startSimMillis();
        return Optional.of(new ScriptedWeatherWindow(
                start + first * 1000L, start + last * 1000L + GRACE_SIM_MILLIS));
    }

    /** Whether a random step due at {@code simMillis} falls inside the window (both ends included). */
    public boolean covers(long simMillis) {
        return simMillis >= fromSimMillis && simMillis <= untilSimMillis;
    }
}
