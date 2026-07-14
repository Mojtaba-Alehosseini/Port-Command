package it.unige.portcommand.assistant;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.1 privacy gate (planning/10 hard constraint): {@link WalkInDialogueSnapshot} must never
 * expose a walk-in's hidden beliefs (min-acceptable price, target price, max-wait patience,
 * personality) — the exact oral-exam attack rehearsed in job5 §5.4 Q2. Reflective, like task
 * 07's privacy gate on outbound ACL content, so a future field addition cannot silently
 * reintroduce a hidden-belief leak.
 */
class WalkInDialogueSnapshotPrivacyTest {

    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "minacceptable", "targetprice", "maxwait", "personality");

    @Test
    void recordComponentsContainNoHiddenBeliefFields() {
        RecordComponent[] components = WalkInDialogueSnapshot.class.getRecordComponents();
        assertTrue(components.length > 0, "WalkInDialogueSnapshot must be a record with components");
        for (RecordComponent component : components) {
            String normalised = component.getName().toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_TOKENS) {
                assertFalse(normalised.contains(forbidden),
                        "WalkInDialogueSnapshot." + component.getName() + " looks like a hidden belief ("
                                + forbidden + ") — hidden beliefs live ONLY in negotiation.WalkInState");
            }
        }
    }

    @Test
    void observableFieldsAreExactlyTheDocumentedSet() {
        // Pinned so a future editor sees this test fail (not silently pass) when the shape
        // changes, and has to consciously re-check the privacy constraint above.
        List<String> expected = List.of("vesselId", "vesselType", "draft", "length", "tonnage", "cargoClass",
                "berthId", "durationHours", "lastVesselOffer", "lastPlayerOffer", "roundsUsed", "timeUsedSec",
                "threatenedWithdrawal");
        List<String> actual = List.of(WalkInDialogueSnapshot.class.getRecordComponents()).stream()
                .map(RecordComponent::getName)
                .toList();
        assertTrue(actual.equals(expected), "record components drifted: " + actual);
    }
}
