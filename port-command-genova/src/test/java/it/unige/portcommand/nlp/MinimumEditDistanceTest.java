package it.unige.portcommand.nlp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The course DP itself (S6, audit P2-1). The table below pins the recurrence against
 * hand-computed values, including PROJECT_DEFINITION §2.2's own acceptance example.
 */
class MinimumEditDistanceTest {

    @ParameterizedTest
    @CsvSource({
            // identical / empty bases
            "carthago, carthago, 0",
            "'', '', 0",
            "'', abc, 3",
            "abc, '', 3",
            // single edits of each kind
            "carthago, cartago, 1",   // deletion (h)
            "carthago, carthagoo, 1", // insertion (o)
            "genoba, genova, 1",      // substitution (b -> v)
            // the §2.2 acceptance example: one insertion (e) + one substitution (b -> v)
            "marsk genoba, maersk genova, 2",
            // the classic textbook pair
            "kitten, sitting, 3",
            // transposition costs 2 under plain Levenshtein (no Damerau extension claimed)
            "gneoa, genoa, 2",
    })
    void distanceMatchesTheHandComputedTable(String a, String b, int expected) {
        assertEquals(expected, MinimumEditDistance.distance(a, b));
        assertEquals(expected, MinimumEditDistance.distance(b, a), "distance must be symmetric");
    }

    @Test
    void withinAppliesTheLengthDifferenceBoundBeforeTheTable() {
        assertTrue(MinimumEditDistance.within("marsk genoba", "maersk genova", 2));
        assertFalse(MinimumEditDistance.within("so", "carthago", 2),
                "a 6-char length gap can never be bridged by 2 edits");
        assertFalse(MinimumEditDistance.within("kitten", "sitting", 2));
    }
}
