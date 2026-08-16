package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7 rewrite (audit P3-10): the old version froze the pre-task-26 NINE-intent world with a
 * one-directional {@code containsAll} — it could not fail on a deleted button, and its name
 * asserted a canon the project no longer has. Now: the TEN-intent canon (the authority is
 * {@code nlp-python/rasa/domain.yml}, "10 intents, 8 entities — EXACTLY per
 * PROJECT_DEFINITION.md 6.3"), exact bidirectional set equality on the mapped intents, and
 * the deliberate absences stated where they are enforced.
 */
class ClarificationButtonsTest {

    /** The ten-intent canon, mirrored from domain.yml (task 26, decision b: 9 -> 10). */
    private static final Set<String> TEN_INTENT_CANON = Set.of(
            "propose_offer", "counter_offer", "accept_deal", "reject_deal", "query_status",
            "set_constraint", "set_policy", "request_help", "cancel_action", "out_of_scope");

    /** The buttons a clarification actually offers — exact, both directions. */
    private static final Set<String> EXPECTED_BUTTON_INTENTS = Set.of(
            "propose_offer", "accept_deal", "reject_deal", "query_status", "request_help");

    @Test
    void defaultOptionsIsExactlyFiveDistinctNonBlankButtons() {
        List<ButtonOption> options = ClarificationButtons.defaultOptions();

        assertEquals(5, options.size());
        Set<String> distinctIntents = options.stream()
                .map(ButtonOption::mappedIntent).collect(Collectors.toSet());
        assertEquals(options.size(), distinctIntents.size(),
                "no two buttons may map to the same intent");
        for (ButtonOption option : options) {
            assertFalse(option.label().isBlank(), "label must not be blank: " + option);
            assertFalse(option.mappedIntent().isBlank(), "mappedIntent must not be blank: " + option);
        }
    }

    @Test
    void theMappedIntentsAreExactlyTheFiveActionableOnes() {
        Set<String> mapped = ClarificationButtons.defaultOptions().stream()
                .map(ButtonOption::mappedIntent)
                .collect(Collectors.toSet());

        // Bidirectional: a deleted button OR a new unvetted one fails, not just an unknown name.
        assertEquals(EXPECTED_BUTTON_INTENTS, mapped,
                "the clarification buttons are exactly the five player-actionable intents");
        assertTrue(TEN_INTENT_CANON.containsAll(mapped),
                "every button intent must exist in the ten-intent canon (domain.yml)");
        // The absences are decisions, not gaps: out_of_scope IS the clarification path (a
        // button for it would be circular); set_policy is a routing label whose real input is
        // the Settings form; propose/counter beyond the generic "Make an offer" are structural
        // intents the DCG owns; set_constraint and cancel_action are typed, not buttoned.
        assertFalse(mapped.contains("out_of_scope"),
                "out_of_scope must never be a button — it is the clarification path itself");
        assertEquals(10, TEN_INTENT_CANON.size(), "the canon itself stays ten intents");
    }
}
