package it.unige.portcommand.scenario.events;

import java.util.List;

import it.unige.portcommand.agents.WeatherAgent;
import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.scenario.GameContext;
import jade.core.behaviours.OneShotBehaviour;
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
        broadcastImmediately(ctx);
        log.info("scripted weather: wind={}kn vis={} swell={} state={}", windKn, visibility, swell, state);
    }

    /**
     * Forces the alert out NOW instead of on the next 15-sim-minute broadcast tick (task 26,
     * 2026-07-27).
     *
     * <p><b>The bug this fixes.</b> The DebugWeatherEvent above lands the new snapshot in the
     * WeatherAgent's cell synchronously, but the INFORM that makes the HarbourMaster hold vessels
     * and CANCEL their escorts only left on the next {@code PeriodicWeatherBroadcastBehaviour}
     * tick — 15 sim-minutes apart. A vessel's channel-to-berth leg is 2 sim-minutes
     * ({@code TransitToBerthBehaviour}), so the alert had roughly a one-in-eight chance of ever
     * catching a vessel in transit, and in the storm scenario it never did: the tanker docked
     * about six sim-minutes before the scripted 32-kn change even fired. The result was a
     * scenario whose EMERGENCY banner read "Escorts cancelled, vessels held offshore" while
     * nothing was cancelled and nothing was held, and a CANCEL performative that
     * PROJECT_DEFINITION §13 claims for {@code storm} but the shipped game never produced.
     * Found by {@code --smoke} (task 26), which is the first thing to have played all three
     * scenarios and taken the union of what they emit.
     *
     * <p>Runs on the WEATHER agent's own thread via a {@code OneShotBehaviour} — this method
     * executes on the HarbourMaster's, and the broadcast touches the weather agent's
     * {@code lastObserved}. Same pattern as {@link TugRefuelCompleteEvent}, including capturing
     * the agent in a final local rather than touching {@code myAgent} (task 19's lesson).
     */
    private void broadcastImmediately(GameContext ctx) {
        if (ctx.directory() == null) {
            return; // stub harness (queue-mechanics unit tests) — nothing to broadcast to
        }
        List<WeatherAgent> agents = ctx.directory().byType(WeatherAgent.class);
        if (agents.isEmpty()) {
            log.warn("scripted weather: no WeatherAgent in the directory — the alert waits for the "
                    + "next periodic broadcast");
            return;
        }
        WeatherAgent weather = agents.get(0);
        weather.addBehaviour(new OneShotBehaviour(weather) {
            @Override
            public void action() {
                weather.broadcastWeatherNow();
            }
        });
    }
}
