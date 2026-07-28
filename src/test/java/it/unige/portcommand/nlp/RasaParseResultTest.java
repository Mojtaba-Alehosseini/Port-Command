package it.unige.portcommand.nlp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code entities} defensive copy must preserve Rasa's response order, because
 * {@code NLPPipeline.entitiesContent} serialises that iteration order straight into the ACL
 * {@code content}. {@code Map.copyOf} would randomise the order per JVM launch (INVARIANTS.md
 * "wire format" — the same rule that governs {@link Frame}); this pins the {@link LinkedHashMap}
 * behaviour so a regression to {@code Map.copyOf} is caught.
 */
class RasaParseResultTest {

    private static EntityHit hit(String value) {
        return new EntityHit("e", value, 0, value.length());
    }

    @Test
    void entityIterationOrderFollowsResponseOrderSoAclContentIsDeterministic() {
        Map<String, EntityHit> ordered = new LinkedHashMap<>();
        ordered.put("vessel_name", hit("Genoa Star"));
        ordered.put("price_expression", hit("1800"));
        ordered.put("time_expression", hit("12 hours"));
        ordered.put("berth_id", hit("berth_3"));

        RasaParseResult r = new RasaParseResult("propose_offer", 0.97, ordered, List.of());

        assertEquals(List.of("vessel_name", "price_expression", "time_expression", "berth_id"),
                List.copyOf(r.entities().keySet()),
                "entity order must match insertion (Rasa response) order, not a per-JVM hash order");
    }

    @Test
    void nullCollectionsBecomeEmptyAndImmutable() {
        RasaParseResult r = new RasaParseResult("nlu_fallback", 0.1, null, null);

        assertEquals(Map.of(), r.entities());
        assertEquals(List.of(), r.intentRanking());
        assertThrows(UnsupportedOperationException.class, () -> r.entities().put("x", hit("y")));
    }

    @Test
    void entitiesAreDefensivelyCopied() {
        Map<String, EntityHit> source = new LinkedHashMap<>();
        source.put("vessel_name", hit("Aurora"));
        RasaParseResult r = new RasaParseResult("query_status", 0.9, source, List.of());

        source.put("price_expression", hit("2000")); // must not leak into the record

        assertEquals(Map.of("vessel_name", hit("Aurora")), r.entities());
    }
}
