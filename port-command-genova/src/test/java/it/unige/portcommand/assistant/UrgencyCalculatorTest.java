package it.unige.portcommand.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge cases for the v1.1 urgency formula (planning/10 §10.1). The 0.4 floor is the
 * load-bearing fix: without it, round 1 zeroes urgency and the Assistant always recommends
 * {@code reject_silent} on the very first Hint click.
 *
 * <p>Since 2026-08-10 (audit P1-9) both time arguments are REAL seconds against the live
 * {@code Settings.timeLimitSeconds} window (valid 30..180, default 60), so the time term is
 * pinned across that whole configurable range rather than at one hard-coded budget — the old
 * {@code DEFAULT_TIME_LIMIT_SEC} constant these tests used to share with production is gone.
 */
class UrgencyCalculatorTest {

    /** The shipped default; the range ends are exercised separately below. */
    private static final double DEFAULT_WINDOW_SEC = 60.0;

    @Test
    void openingOfferFloorsAtPointFour() {
        double urgency = UrgencyCalculator.compute(0, UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                0.0, DEFAULT_WINDOW_SEC, false);
        assertEquals(0.4, urgency, 1e-9, "round 1 / no elapsed time / no threat must floor at 0.4, not 0.0");
    }

    @ParameterizedTest(name = "an unstarted dialogue floors at 0.4 for a {0}s window")
    @ValueSource(doubles = {30.0, 60.0, 180.0})
    void anUnstartedDialogueFloorsAtPointFourAtEveryConfigurableWindow(double windowSec) {
        assertEquals(0.4, UrgencyCalculator.compute(0, UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                0.0, windowSec, false), 1e-9, "t=0 must floor regardless of the configured window");
    }

    @ParameterizedTest(name = "elapsed == the {0}s window contributes the full time term")
    @ValueSource(doubles = {30.0, 60.0, 180.0})
    void aFullyElapsedWindowContributesItsWholeTermAtEveryConfigurableLimit(double windowSec) {
        // Time term alone: 0.4 + 0.6 * (0.4 * 1.0) = 0.64, at round 0 with no threat.
        double atLimit = UrgencyCalculator.compute(0, UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                windowSec, windowSec, false);
        assertEquals(0.64, atLimit, 1e-9,
                "a fully-spent window must contribute its whole 0.4 weight, window=" + windowSec);

        // And with rounds and a threat on top, the same instant reaches the cap.
        double saturated = UrgencyCalculator.compute(UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                UrgencyCalculator.DEFAULT_ROUND_LIMIT, windowSec, windowSec, true);
        assertEquals(1.0, saturated, 1e-9, "window=" + windowSec);
    }

    @ParameterizedTest(name = "urgency rises monotonically across a {0}s window")
    @ValueSource(doubles = {30.0, 60.0, 180.0})
    void urgencyRisesMonotonicallyWithElapsedTime(double windowSec) {
        double previous = -1.0;
        for (int step = 0; step <= 10; step++) {
            double elapsed = windowSec * step / 10.0;
            double urgency = UrgencyCalculator.compute(1, UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                    elapsed, windowSec, false);
            assertTrue(urgency > previous,
                    "urgency must strictly rise with elapsed time (window=" + windowSec
                            + ", elapsed=" + elapsed + ", got " + urgency + " after " + previous + ")");
            previous = urgency;
        }
    }

    @Test
    void aShorterWindowMakesTheSameElapsedTimeMoreUrgent() {
        // Non-vacuity for the "live Settings value, not a hard-coded 60" rider: the window must
        // actually change the answer, in the intuitive direction.
        double tightDeadline = UrgencyCalculator.compute(1, 4, 15.0, 30.0, false);
        double looseDeadline = UrgencyCalculator.compute(1, 4, 15.0, 180.0, false);
        assertTrue(tightDeadline > looseDeadline,
                "15s into a 30s window must be more urgent than 15s into a 180s one ("
                        + tightDeadline + " vs " + looseDeadline + ")");
    }

    @Test
    void fullyExhaustedAndThreatenedCapsAtOne() {
        double urgency = UrgencyCalculator.compute(UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                UrgencyCalculator.DEFAULT_ROUND_LIMIT, DEFAULT_WINDOW_SEC, DEFAULT_WINDOW_SEC, true);
        assertEquals(1.0, urgency, 1e-9);
    }

    @Test
    void overrunInputsStillClampToOne() {
        // rounds/time far past the limit, e.g. a stale snapshot — must not exceed 1.0.
        double urgency = UrgencyCalculator.compute(50, UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                50_000.0, DEFAULT_WINDOW_SEC, true);
        assertEquals(1.0, urgency, 1e-9);
    }

    @ParameterizedTest(name = "roundsUsed={0} -> urgency in [0.4, 1.0]")
    @CsvSource({"0", "1", "2", "3", "4"})
    void everyRoundStaysInRange(int roundsUsed) {
        double urgency = UrgencyCalculator.compute(roundsUsed, UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                0.0, DEFAULT_WINDOW_SEC, false);
        assertTrue(urgency >= 0.4 && urgency <= 1.0, "urgency=" + urgency + " out of [0.4, 1.0]");
    }

    @Test
    void higherRoundsUsedNeverDecreasesUrgency() {
        double round1 = UrgencyCalculator.compute(1, 4, 0.0, 60.0, false);
        double round3 = UrgencyCalculator.compute(3, 4, 0.0, 60.0, false);
        assertTrue(round3 >= round1, "more rounds used must not lower urgency");
    }

    @Test
    void rejectsNonPositiveRoundLimit() {
        assertThrows(IllegalArgumentException.class, () -> UrgencyCalculator.compute(0, 0, 0.0, 60.0, false));
    }

    @Test
    void rejectsNonPositiveTimeLimit() {
        assertThrows(IllegalArgumentException.class, () -> UrgencyCalculator.compute(0, 4, 0.0, 0.0, false));
    }
}
