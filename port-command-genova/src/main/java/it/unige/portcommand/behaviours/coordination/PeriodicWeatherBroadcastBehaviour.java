package it.unige.portcommand.behaviours.coordination;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.WeatherSnapshot;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Broadcasts the current weather to all {@code weather-subscriber} agents every 15
 * sim-minutes, and — when the wind crosses a discrete limit (30/35/40/45 kn) — sends
 * an additional {@code priority=high} INFORM to the HarbourMaster. The current
 * snapshot is shared (read-only here) with {@code WeatherEvolutionBehaviour} via the
 * {@link AtomicReference}.
 *
 * <p><b>Task 19 cadence fix.</b> The tick was 60 sim-seconds, which at the default
 * 300-real-second game day compresses to ~0.2&nbsp;s of wall time — hundreds of
 * broadcasts per real second, needless load. It is now 15 sim-minutes (~3&nbsp;s wall),
 * still finer-grained than {@code WeatherEvolutionBehaviour}'s 60-sim-minute evolution
 * so no weather change is ever missed between broadcasts, while the threshold-alert rate
 * (which is what reaches the comm log) drops to at most one per weather change.
 */
public final class PeriodicWeatherBroadcastBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(PeriodicWeatherBroadcastBehaviour.class);
    private static final long TICK_SIM_MILLIS = 900_000L; // 15 sim-minutes
    // Wind-based limits (kn). Intentionally state-agnostic: the alert fires on the wind
    // crossing, so a cloudy step into [30,32] trips it too — not only "stormy" weather.
    private static final int[] WIND_THRESHOLDS = {30, 35, 40, 45};

    private final AtomicReference<WeatherSnapshot> current;
    private final ServiceLocator locator;
    private double lastBroadcastWind = Double.NaN;

    public PeriodicWeatherBroadcastBehaviour(Agent agent, SimClock simClock,
                                             AtomicReference<WeatherSnapshot> current, ServiceLocator locator) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.current = current;
        this.locator = locator;
    }

    @Override
    protected void onSimTick() {
        WeatherSnapshot w = current.get();
        if (w == null) {
            return;
        }
        broadcast(w);
        if (thresholdCrossed(lastBroadcastWind, w.wind())) {
            alertHarbourMaster(w);
        }
        lastBroadcastWind = w.wind();
    }

    private void broadcast(WeatherSnapshot w) {
        List<AID> subscribers = locator.findAll("weather-subscriber");
        if (subscribers.isEmpty()) {
            return;
        }
        ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
        subscribers.forEach(inform::addReceiver);
        inform.setContent(TerminalJson.write(Map.of(
                "wind_knots", w.wind(),
                "visibility", w.visibility(),
                "swell", w.swell(),
                "state", w.state(),
                "sim_time", w.simTime())));
        myAgent.send(inform);
        log.debug("weather broadcast wind={} state={} -> {} subscriber(s)", w.wind(), w.state(), subscribers.size());
    }

    private void alertHarbourMaster(WeatherSnapshot w) {
        Optional<AID> hm = locator.findUnique("harbour-master");
        if (hm.isEmpty()) {
            log.warn("no harbour-master in DF; dropping weather threshold alert (wind {})", w.wind());
            return;
        }
        ACLMessage alert = MessageFactory.create(ACLMessage.INFORM);
        alert.addReceiver(hm.get());
        // v1.1: visibility + swell ride along so the HarbourMaster can evaluate
        // operationSafe/swellWithinLimit/weatherStateUnsafe from this SAME reading,
        // instead of risking a second, possibly-stale query (task 11).
        alert.setContent(TerminalJson.write(Map.of(
                "event", "weather_threshold", "wind_knots", w.wind(), "visibility", w.visibility(),
                "swell", w.swell(), "state", w.state())));
        alert.addUserDefinedParameter("priority", "high");
        // Lets HandleWeatherAlertBehaviour match by protocol instead of sender AID —
        // avoids a DF-lookup timing race (task 11 adversarial review finding).
        // Same package, no import needed.
        alert.setProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL);
        myAgent.send(alert);
        log.info("weather threshold crossed (wind {}) -> high-priority INFORM to harbour-master", w.wind());
    }

    private static boolean thresholdCrossed(double prevWind, int currentWind) {
        if (Double.isNaN(prevWind)) {
            return false; // first broadcast establishes the baseline
        }
        for (int threshold : WIND_THRESHOLDS) {
            if (prevWind < threshold && currentWind >= threshold) {
                return true;
            }
        }
        return false;
    }
}
