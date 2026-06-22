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
 * Broadcasts the current weather to all {@code weather-subscriber} agents every 60
 * sim-seconds, and — when the wind crosses a discrete limit (30/35/40/45 kn) — sends
 * an additional {@code priority=high} INFORM to the HarbourMaster. The current
 * snapshot is shared (read-only here) with {@code WeatherEvolutionBehaviour} via the
 * {@link AtomicReference}.
 */
public final class PeriodicWeatherBroadcastBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(PeriodicWeatherBroadcastBehaviour.class);
    private static final long TICK_SIM_MILLIS = 60_000L; // 60 sim-seconds
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
        alert.setContent(TerminalJson.write(Map.of(
                "event", "weather_threshold", "wind_knots", w.wind(), "state", w.state())));
        alert.addUserDefinedParameter("priority", "high");
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
