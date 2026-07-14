package it.unige.portcommand.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge cases for the v1.1 urgency formula (planning/10 §10.1). The 0.4 floor is the
 * load-bearing fix: without it, round 1 zeroes urgency and the Assistant always recommends
 * {@code reject_silent} on the very first Hint click.
 */
class UrgencyCalculatorTest {

    @Test
    void openingOfferFloorsAtPointFour() {
        double urgency = UrgencyCalculator.compute(0, UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                0.0, UrgencyCalculator.DEFAULT_TIME_LIMIT_SEC, false);
        assertEquals(0.4, urgency, 1e-9, "round 1 / no elapsed time / no threat must floor at 0.4, not 0.0");
    }

    @Test
    void fullyExhaustedAndThreatenedCapsAtOne() {
        double urgency = UrgencyCalculator.compute(UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                UrgencyCalculator.DEFAULT_ROUND_LIMIT, UrgencyCalculator.DEFAULT_TIME_LIMIT_SEC,
                UrgencyCalculator.DEFAULT_TIME_LIMIT_SEC, true);
        assertEquals(1.0, urgency, 1e-9);
    }

    @Test
    void overrunInputsStillClampToOne() {
        // rounds/time far past the limit, e.g. a stale snapshot — must not exceed 1.0.
        double urgency = UrgencyCalculator.compute(50, UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                50_000.0, UrgencyCalculator.DEFAULT_TIME_LIMIT_SEC, true);
        assertEquals(1.0, urgency, 1e-9);
    }

    @ParameterizedTest(name = "roundsUsed={0} -> urgency in [0.4, 1.0]")
    @CsvSource({"0", "1", "2", "3", "4"})
    void everyRoundStaysInRange(int roundsUsed) {
        double urgency = UrgencyCalculator.compute(roundsUsed, UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                0.0, UrgencyCalculator.DEFAULT_TIME_LIMIT_SEC, false);
        assertTrue(urgency >= 0.4 && urgency <= 1.0, "urgency=" + urgency + " out of [0.4, 1.0]");
    }

    @Test
    void higherRoundsUsedNeverDecreasesUrgency() {
        double round1 = UrgencyCalculator.compute(1, 4, 0.0, 600.0, false);
        double round3 = UrgencyCalculator.compute(3, 4, 0.0, 600.0, false);
        assertTrue(round3 >= round1, "more rounds used must not lower urgency");
    }

    @Test
    void rejectsNonPositiveRoundLimit() {
        assertThrows(IllegalArgumentException.class, () -> UrgencyCalculator.compute(0, 0, 0.0, 600.0, false));
    }

    @Test
    void rejectsNonPositiveTimeLimit() {
        assertThrows(IllegalArgumentException.class, () -> UrgencyCalculator.compute(0, 4, 0.0, 0.0, false));
    }
}
