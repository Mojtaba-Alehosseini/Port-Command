package it.unige.portcommand.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small bounded (last ~20) cache of past {@link Recommendation}s keyed by decision id, so a
 * "Why?" click ({@code ExplainEventBehaviour}) can answer without recomputation (planning/10
 * §10.8b). Written by {@code RecommendOnDemandBehaviour}/{@code AutopilotExecuteBehaviour}
 * whenever they compute a fresh recommendation.
 */
public final class RecommendationCache {

    private static final int MAX_ENTRIES = 20;

    private final Map<String, Recommendation> entries =
            Collections.synchronizedMap(new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Recommendation> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public void put(String decisionId, Recommendation recommendation) {
        entries.put(decisionId, recommendation);
    }

    public Optional<Recommendation> get(String decisionId) {
        return Optional.ofNullable(entries.get(decisionId));
    }
}
