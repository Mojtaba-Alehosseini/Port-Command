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
 * Evolves the weather every 60 sim-minutes: applies any due scripted override
 * (scenario determinism), else takes a seeded Markov step and maps the new state to
 * a wind/visibility/swell reading. Writes the shared {@link AtomicReference} that
 * {@code PeriodicWeatherBroadcastBehaviour} reads. Deterministic for a given seed
 * (the RNG is a {@code RandomSource.forStream("weather")} sub-stream).
 *
 * <p><b>Task 19 cadence + persistence fix.</b> The tick was 5 sim-minutes, which at the
 * default 300-real-second game day compresses to ~1&nbsp;s of wall time — weather
 * changed every second and, because each step re-rolled a fresh uniform wind across the
 * whole state band, the wind teleported (e.g. 30&nbsp;→&nbsp;54&nbsp;→&nbsp;31) and
 * repeatedly flapped the 30/35/40/45&nbsp;kn thresholds, flooding the HarbourMaster's
 * comm log with alerts. Now: (1) the tick is 60 sim-minutes (~12&nbsp;s wall at the
 * default mapping) so weather changes at a human pace, and (2) the wind {@link #drift}s
 * from its previous value toward the new state's band by a bounded step instead of
 * teleporting, so a storm ramps up and subsides gradually and threshold crossings are
 * occasional, not every tick. Paired with {@code TransitionMatrix.defaults()}'s
 * rarer-shorter-storms recalibration; the wall-time-compression ROOT CAUSE (frozen
 * {@code SimClock} until the task-24 advancer) stays task 24's.
 */
public final class WeatherEvolutionBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(WeatherEvolutionBehaviour.class);
    private static final long TICK_SIM_MILLIS = 3_600_000L; // 60 sim-minutes (task-19 recalibration)
    private static final long SIM_MILLIS_PER_MINUTE = 60_000L;
    private static final int MAX_WIND_STEP_KN = 6; // per-tick wind drift cap (persistence)

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
        if (simClock().isPaused()) {
            return; // pause gate (task 24): weather does not evolve while the game is paused
        }
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
        current.set(stateToSnapshot(nextState, now, w.wind()));
        log.debug("weather Markov step {} -> {}", w.state(), nextState);
    }

    private WeatherSnapshot stateToSnapshot(String state, long simTime, int prevWind) {
        return switch (state) {
            case "cloudy" -> new WeatherSnapshot(drift(prevWind, 15, 32), "fair", 1.5, "cloudy", simTime);
            case "stormy" -> new WeatherSnapshot(drift(prevWind, 30, 55), "poor", 3.5, "stormy", simTime);
            default -> new WeatherSnapshot(drift(prevWind, 5, 20), "good", 0.5, "sunny", simTime);
        };
    }

    /**
     * Moves {@code prevWind} toward a random target inside {@code [lo,hi]} by at most
     * {@link #MAX_WIND_STEP_KN} knots, then clamps into the band. WITHIN a band this gives the
     * wind persistence that stops the threshold flapping (small step, no teleport). Across a
     * DISJOINT band (a state transition, e.g. sunny[5,20]→stormy[30,55]) the final clamp overrides
     * the step cap and snaps to the nearer band edge — a single legitimate crossing per state
     * change, which is the intended "weather just changed" signal, not the intra-state flapping the
     * cap targets (adversarial review L2, 2026-07-17).
     */
    private int drift(int prevWind, int lo, int hi) {
        int target = lo + rng.nextInt(hi - lo + 1);
        int step = Math.max(-MAX_WIND_STEP_KN, Math.min(MAX_WIND_STEP_KN, target - prevWind));
        return Math.max(lo, Math.min(hi, prevWind + step));
    }
}
