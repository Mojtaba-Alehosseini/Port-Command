package it.unige.portcommand.nlp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed result of a Rasa {@code /model/parse} call (task 12, the single NLU pipeline on
 * port 5005). {@code entities} is keyed by entity name; when Rasa reports the same entity
 * name twice, the last one in response order wins (planning/14 §14.1).
 *
 * <p>The {@code entities} copy is a {@link LinkedHashMap}, NOT {@code Map.copyOf}: this map is
 * serialised into ACL {@code content} by {@code NLPPipeline.entitiesContent}, and
 * {@code Map.copyOf} randomises iteration order per JVM launch — the same Rasa reply would then
 * become different ACL content between runs (INVARIANTS.md "wire format", the {@link Frame} fix).
 * Preserving Rasa's response order also keeps the "last one wins" rule above meaningful.
 */
public record RasaParseResult(
        String intentName,
        double confidence,
        Map<String, EntityHit> entities,
        List<RankedIntent> intentRanking) {

    public RasaParseResult {
        entities = entities == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(entities));
        intentRanking = intentRanking == null ? List.of() : List.copyOf(intentRanking);
    }
}
