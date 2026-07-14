package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Map;

/**
 * Typed result of a Rasa {@code /model/parse} call (task 12, the single NLU pipeline on
 * port 5005). {@code entities} is keyed by entity name; when Rasa reports the same entity
 * name twice, the last one in response order wins (planning/14 §14.1).
 */
public record RasaParseResult(
        String intentName,
        double confidence,
        Map<String, EntityHit> entities,
        List<RankedIntent> intentRanking) {

    public RasaParseResult {
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        intentRanking = intentRanking == null ? List.of() : List.copyOf(intentRanking);
    }
}
