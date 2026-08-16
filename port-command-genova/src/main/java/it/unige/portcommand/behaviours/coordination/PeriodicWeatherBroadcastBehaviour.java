package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.WeatherAlertPolicy;
import it.unige.portcommand.agents.WeatherAlertPolicy.Direction;
import it.unige.portcommand.agents.WeatherSnapshot;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the HarbourMaster a weather snapshot every 15 sim-minutes (audit P1-4 — it used to
 * address a {@code weather-subscriber} DF type nothing registers, so the channel was dead;
 * see {@link #broadcast}), publishes the GUI's {@link WeatherChangeEvent} (task 24 — the map's
 * weather chip goes live), and — when {@link WeatherAlertPolicy} reports a genuine
 * severity transition — sends the HarbourMaster an alert on the
 * {@code WEATHER_ALERT_PROTOCOL}, in BOTH directions:
 * <ul>
 *   <li>{@code weather_threshold}, {@code priority=high} — conditions crossed a
 *       limit upward; {@code HandleWeatherAlertBehaviour} runs the hold sweep.</li>
 *   <li>{@code weather_clear} (normal priority) — conditions recovered a limit;
 *       the hold behaviour re-evaluates weather-held vessels and re-dispatches
 *       their tug CNP. This is the signal whose absence permanently stranded
 *       held vessels (task-19 finding, filed at planning/24).</li>
 * </ul>
 * The current snapshot is shared (read-only here) with
 * {@code WeatherEvolutionBehaviour} via the {@link AtomicReference}.
 *
 * <p><b>Task 19 cadence fix.</b> The tick was 60 sim-seconds, which at the default
 * 300-real-second game day compresses to ~0.2&nbsp;s of wall time — hundreds of
 * broadcasts per real second, needless load. It is now 15 sim-minutes (~3&nbsp;s wall),
 * still finer-grained than {@code WeatherEvolutionBehaviour}'s 60-sim-minute evolution
 * so no weather change is ever missed between broadcasts.
 *
 * <p><b>Task 24 threshold/priority fix.</b> Alerts fire only on genuine 30/35/40/45
 * (or swell/state) crossings between consecutive readings, and only the WORSENING
 * direction carries {@code priority=high} — the task-18 checkpoint's
 * "every broadcast paraphrases as URGENT" is structurally gone. Paused game
 * (SimClock paused) → no broadcasts, no alerts, no GUI events.
 */
public final class PeriodicWeatherBroadcastBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(PeriodicWeatherBroadcastBehaviour.class);
    private static final long TICK_SIM_MILLIS = 900_000L; // 15 sim-minutes

    private final AtomicReference<WeatherSnapshot> current;
    private final ServiceLocator locator;
    private final EventBus eventBus;
    /** The previous reading — {@link WeatherAlertPolicy}'s baseline. Null until the first tick. */
    private WeatherSnapshot lastObserved;

    public PeriodicWeatherBroadcastBehaviour(Agent agent, SimClock simClock,
                                             AtomicReference<WeatherSnapshot> current, ServiceLocator locator,
                                             EventBus eventBus) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.current = current;
        this.locator = locator;
        this.eventBus = eventBus;
    }

    /**
     * Runs one broadcast cycle immediately, off the ticker.
     *
     * <p><b>2026-07-27 (task 26).</b> A scripted {@code WeatherChange} adopts its snapshot into
     * the shared cell synchronously, but the ALERT that actually holds vessels only left on the
     * next periodic tick — up to 15 sim-minutes later. A vessel's channel-to-berth transit is
     * {@code TransitToBerthBehaviour.TRANSIT_SIM_MILLIS} = 2 sim-minutes, so the alert essentially
     * never caught a vessel in transit, and the storm scenario's CANCEL cascade — the one the
     * scenario's own EMERGENCY banner announces as "Escorts cancelled, vessels held offshore",
     * and the one PROJECT_DEFINITION §13 counts on for the CANCEL performative — could not fire.
     * The scripted event now calls this so the change takes effect at the instant it is scripted
     * for. Idempotent with respect to the ticker: {@code lastObserved} advances here, so the next
     * periodic tick simply sees no transition.
     */
    public void broadcastNow() {
        onSimTick();
    }

    @Override
    protected void onSimTick() {
        if (simClock().isPaused()) {
            return; // pause gate (task 24): a paused game broadcasts nothing
        }
        WeatherSnapshot w = current.get();
        if (w == null) {
            return;
        }
        broadcast(w);
        Optional<Direction> transition = WeatherAlertPolicy.transition(lastObserved, w);
        transition.ifPresent(direction -> alertHarbourMaster(w, direction));
        eventBus.publish(new WeatherChangeEvent(w.wind(), w.visibility(), w.swell(), w.state(),
                transition.isPresent()));
        lastObserved = w;
    }

    /**
     * The 15-sim-minute weather snapshot, sent to the HarbourMaster hub (2026-08-10, audit P1-4).
     *
     * <p>It used to be addressed to a DF service type {@code "weather-subscriber"} that <b>no agent
     * has ever registered</b>, so {@code findAll} always came back empty and the INFORM was never
     * built, let alone sent — the whole periodic channel was dead, and only the transition-driven
     * alert path below did anything.
     *
     * <p>Routed to the hub rather than to vessels or tugs because nothing on those agents can
     * consume weather cheaply and honestly: a tug's {@code topSpeedKnots} is fixed at setup and
     * scaling only one of the bid-ETA/transit pair would break the agreement {@code TugMathTest}
     * pins, while vessel transit is duration-driven off a {@code final} arrival instant the
     * save/resume contract reads. The hub, by contrast, has a real use for it — see below — and
     * the HarbourMaster's send/receive is the comm log's single instrumentation point, so a
     * Weather-to-vessel message would have been invisible there anyway.
     *
     * <p><b>What it actually does:</b> alerts fire only on {@link WeatherAlertPolicy} TRANSITIONS,
     * so a vessel that enters transit while the weather is <em>already</em> bad is never held —
     * the storm began before it arrived and no further transition is coming. This periodic
     * snapshot re-runs the hub's existing hold gate over every tracked vessel, which is exactly
     * the set the transition path misses. It carries the {@code weather_periodic} discriminator so
     * the handler can tell it from a real alert, and rides the SAME protocol tag, so no new
     * template, protocol or behaviour is needed to receive it.
     */
    private void broadcast(WeatherSnapshot w) {
        Optional<AID> hub = locator.findUnique("harbour-master");
        if (hub.isEmpty()) {
            log.debug("no harbour-master in DF yet; skipping the periodic weather snapshot");
            return;
        }
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        inform.addReceiver(hub.get());
        inform.setContent(TerminalJson.write(Map.of(
                "event", "weather_periodic",
                "wind_knots", w.wind(),
                "visibility", w.visibility(),
                "swell", w.swell(),
                "state", w.state(),
                "sim_time", w.simTime())));
        inform.setProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL);
        myAgent.send(inform);
        log.debug("periodic weather snapshot wind={} state={} -> harbour-master", w.wind(), w.state());
    }

    private void alertHarbourMaster(WeatherSnapshot w, Direction direction) {
        Optional<AID> hm = locator.findUnique("harbour-master");
        if (hm.isEmpty()) {
            log.warn("no harbour-master in DF; dropping weather {} alert (wind {})", direction, w.wind());
            return;
        }
        boolean worsened = direction == Direction.WORSENED;
        ACLMessage alert = MessageFactory.create(ACLMessage.INFORM);
        alert.addReceiver(hm.get());
        // v1.1: visibility + swell ride along so the HarbourMaster can evaluate
        // operationSafe/swellWithinLimit/weatherStateUnsafe from this SAME reading,
        // instead of risking a second, possibly-stale query (task 11).
        // S5 (R18 wired): the alert names the rule that produced it. The band comparison that
        // decided this transition IS threshold_crossed/2's enumeration (WeatherAlertPolicy.windBand
        // delegates to PrologQueries.windThresholdsCrossed), so the citation is the rule actually
        // consulted, and the Paraphraser renders it into the comm log line.
        alert.setContent(TerminalJson.write(Map.of(
                "event", worsened ? "weather_threshold" : "weather_clear",
                "reason", worsened ? "threshold_crossed [R18]" : "threshold_cleared [R18]",
                "wind_knots", w.wind(), "visibility", w.visibility(),
                "swell", w.swell(), "state", w.state())));
        if (worsened) {
            // Only the worsening direction is URGENT (task 24 priority fix).
            alert.addUserDefinedParameter("priority", "high");
        }
        // Lets HandleWeatherAlertBehaviour match by protocol instead of sender AID —
        // avoids a DF-lookup timing race (task 11 adversarial review finding).
        // Same package, no import needed.
        alert.setProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL);
        myAgent.send(alert);
        publishNotification(w, worsened);
        log.info("weather {} (wind {} kn, {}) -> INFORM to harbour-master", direction, w.wind(), w.state());
    }

    private void publishNotification(WeatherSnapshot w, boolean worsened) {
        String text = String.format(Locale.ROOT, "%s: %d kn, %s, swell %.1f m",
                worsened ? "Weather worsening" : "Weather clearing", w.wind(), w.state(), w.swell());
        eventBus.publish(new NotificationEvent(text,
                worsened ? NotificationEvent.Severity.WARNING : NotificationEvent.Severity.INFO,
                w.simTime()));
    }
}
