package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClarificationButtonsTest {

    @Test
    void defaultOptionsIsExactlyFiveWithNonBlankLabelsAndIntents() {
        List<ButtonOption> options = ClarificationButtons.defaultOptions();

        assertEquals(5, options.size());
        for (ButtonOption option : options) {
            assertFalse(option.label().isBlank(), "label must not be blank: " + option);
            assertFalse(option.mappedIntent().isBlank(), "mappedIntent must not be blank: " + option);
        }
    }

    @Test
    void everyMappedIntentIsOneOfTheNineFrozenIntents() {
        Set<String> frozenIntents = Set.of(
                "propose_offer", "counter_offer", "accept_deal", "reject_deal", "query_status",
                "set_constraint", "set_policy", "request_help", "cancel_action");

        Set<String> mapped = ClarificationButtons.defaultOptions().stream()
                .map(ButtonOption::mappedIntent)
                .collect(Collectors.toSet());

        assertTrue(frozenIntents.containsAll(mapped), "unknown intent in " + mapped);
    }
}
