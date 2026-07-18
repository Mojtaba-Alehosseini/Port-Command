package it.unige.portcommand.scenario.events;

import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.scenario.GameContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scripted weather force. Distinct class from the GUI bus event
 * {@code gui.events.WeatherChangeEvent} despite the related name (planning/23 §23.4).
 *
 * <p><b>Reconciled mechanism (2026-07-18):</b> the §23.4 sketch ("send an INFORM to
 * WeatherAgent") predates task 24's {@code DebugWeatherOverrideBehaviour} — the
 * WeatherAgent already subscribes to {@link DebugWeatherEvent} and adopts the forced
 * snapshot into its shared cell (CALLER_THREAD, so it lands synchronously here). The
 * next {@code PeriodicWeatherBroadcastBehaviour} tick (≤ 15 sim-min) then drives
 * threshold alerts, holds and re-dispatches through the REAL pipeline. The canonical
 * {@link WeatherChangeEvent} is additionally published immediately so the map overlay
 * and HUD chip react at the scripted instant rather than a tick later
 * ({@code thresholdCrossed=false} — genuine crossings stay the broadcast's call).
 *
 * <p>Vocabulary is the canonical task-09 {@code WeatherSnapshot} one: category
 * visibility {@code good|fair|poor}, Markov state {@code sunny|cloudy|stormy}
 * (the sketch's {@code visibilityNm} double predates it).
 */
public record WeatherChangeScriptedEvent(
        long simTimeSeconds,
        int windKn,
        String visibility,
        double swell,
        String state) implements ScriptedEvent {

    private static final Logger log = LoggerFactory.getLogger(WeatherChangeScriptedEvent.class);

    @Override
    public void apply(GameContext ctx) {
        ctx.eventBus().publish(new DebugWeatherEvent(windKn, visibility, swell, state));
        ctx.eventBus().publish(new WeatherChangeEvent(windKn, visibility, swell, state, false));
        log.info("scripted weather: wind={}kn vis={} swell={} state={}", windKn, visibility, swell, state);
    }
}
