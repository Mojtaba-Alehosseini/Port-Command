package it.unige.portcommand.agents;

import java.util.List;
import java.util.Map;

/**
 * The weather Markov chain's 3×3 transition matrix over {@code sunny/cloudy/stormy}.
 * Tunable via {@code defaults.json} (task 23); rides inside {@code WeatherInitArgs}
 * as a Jackson {@code Map<from, Map<to, probability>>}.
 */
public record TransitionMatrix(Map<String, Map<String, Double>> rows) {

    /** Canonical state order — fixes the cumulative-sampling sequence for determinism. */
    public static final List<String> STATES = List.of("sunny", "cloudy", "stormy");

    /**
     * Task 19 play-test recalibration: the original rows (sunny 0.70/0.25/0.05,
     * cloudy 0.30/0.55/0.15, stormy 0.10/0.50/0.40) put the chain in a storm ~13% of all
     * steps with long storm streaks — and with the sim clock frozen until task 24, steps
     * fire on compressed WALL time (~a game-day of weather per 5 real minutes), so nearly
     * every tug escort was cancelled mid-flight by a weather hold. Storms are now rare and
     * short (steady-state ≈4%): dramatic when they land, not a permanent hurricane. Task
     * 24 fixes the underlying wall-time compression; task 23 makes these rows config-file
     * data.
     */
    public static TransitionMatrix defaults() {
        return new TransitionMatrix(Map.of(
                "sunny", Map.of("sunny", 0.85, "cloudy", 0.13, "stormy", 0.02),
                "cloudy", Map.of("sunny", 0.40, "cloudy", 0.52, "stormy", 0.08),
                "stormy", Map.of("sunny", 0.15, "cloudy", 0.55, "stormy", 0.30)));
    }

    /**
     * Next state from {@code current}, sampling its row's cumulative distribution
     * with {@code roll} in [0,1). Deterministic for a given (current, roll).
     */
    public String nextState(String current, double roll) {
        Map<String, Double> row = rows.get(current);
        if (row == null) {
            return current;
        }
        double cumulative = 0.0;
        for (String state : STATES) {
            cumulative += row.getOrDefault(state, 0.0);
            if (roll < cumulative) {
                return state;
            }
        }
        return STATES.get(STATES.size() - 1);
    }
}
