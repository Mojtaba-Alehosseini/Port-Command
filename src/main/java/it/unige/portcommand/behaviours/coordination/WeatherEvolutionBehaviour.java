package it.unige.portcommand.behaviours.coordination;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.InitArgs.WeatherInitArgs.ScriptedWeather;
import it.unige.portcommand.agents.TransitionMatrix;
import it.unige.portcommand.agents.WeatherSnapshot;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evolves the weather every 5 sim-minutes: applies any due scripted override
 * (scenario determinism), else takes a seeded Markov step and maps the new state to
 * a wind/visibility/swell reading. Writes the shared {@link AtomicReference} that
 * {@code PeriodicWeatherBroadcastBehaviour} reads. Deterministic for a given seed
 * (the RNG is a {@code RandomSource.forStream("weather")} sub-stream).
 */
public final class WeatherEvolutionBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(WeatherEvolutionBehaviour.class);
    private static final long TICK_SIM_MILLIS = 300_000L; // 5 sim-minutes
    private static final long SIM_MILLIS_PER_MINUTE = 60_000L;

    private final AtomicReference<WeatherSnapshot> current;
    private final TransitionMatrix matrix;
    private final Random rng;
    private final List<ScriptedWeather> overrides;
    private int nextOverride = 0;

    public WeatherEvolutionBehaviour(Agent agent, SimClock simClock, AtomicReference<WeatherSnapshot> current,
                                     TransitionMatrix matrix, Random rng, List<ScriptedWeather> overrides) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.current = current;
        this.matrix = matrix;
        this.rng = rng;
        this.overrides = overrides.stream()
                .sorted(Comparator.comparingInt(ScriptedWeather::simMinute)).toList();
    }

    @Override
    protected void onSimTick() {
        long now = simClock().nowSimMillis();
        int simMinute = (int) (now / SIM_MILLIS_PER_MINUTE);

        if (nextOverride < overrides.size() && overrides.get(nextOverride).simMinute() <= simMinute) {
            WeatherSnapshot s = overrides.get(nextOverride).snapshot();
            current.set(new WeatherSnapshot(s.wind(), s.visibility(), s.swell(), s.state(), now));
            nextOverride++;
            log.info("scripted weather applied at sim-min {}: wind={} state={}", simMinute, s.wind(), s.state());
            return;
        }

        WeatherSnapshot w = current.get();
        String nextState = matrix.nextState(w.state(), rng.nextDouble());
        current.set(stateToSnapshot(nextState, now));
        log.debug("weather Markov step {} -> {}", w.state(), nextState);
    }

    private WeatherSnapshot stateToSnapshot(String state, long simTime) {
        return switch (state) {
            case "cloudy" -> new WeatherSnapshot(windInRange(15, 32), "fair", 1.5, "cloudy", simTime);
            case "stormy" -> new WeatherSnapshot(windInRange(30, 55), "poor", 3.5, "stormy", simTime);
            default -> new WeatherSnapshot(windInRange(5, 20), "good", 0.5, "sunny", simTime);
        };
    }

    private int windInRange(int lo, int hi) {
        return lo + rng.nextInt(hi - lo + 1);
    }
}
