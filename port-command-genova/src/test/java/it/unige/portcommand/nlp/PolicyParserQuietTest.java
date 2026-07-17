package it.unige.portcommand.nlp;

import java.util.Optional;

import it.unige.portcommand.artifacts.PolicyRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 22: {@link PolicyParser#parseQuietly} — the no-publish restore path. */
class PolicyParserQuietTest {

    @Test
    void parseQuietlyRebuildsTheRuleWithoutTouchingAnyBus() {
        Optional<PolicyRule> rule = new PolicyParser()
                .parseQuietly("auto accept if price > 2200 for tankers");
        assertTrue(rule.isPresent());
        assertEquals("auto accept if price > 2200 for tankers", rule.get().trigger(),
                "the trigger text round-trips verbatim — it IS what the save file stores");
        assertEquals("accept", rule.get().action().kind());
    }

    @Test
    void aTriggerThatNoLongerParsesComesBackEmptyQuietly() {
        assertTrue(new PolicyParser().parseQuietly("gibberish policy text").isEmpty());
    }
}
