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

    public static TransitionMatrix defaults() {
        return new TransitionMatrix(Map.of(
                "sunny", Map.of("sunny", 0.70, "cloudy", 0.25, "stormy", 0.05),
                "cloudy", Map.of("sunny", 0.30, "cloudy", 0.55, "stormy", 0.15),
                "stormy", Map.of("sunny", 0.10, "cloudy", 0.50, "stormy", 0.40)));
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
