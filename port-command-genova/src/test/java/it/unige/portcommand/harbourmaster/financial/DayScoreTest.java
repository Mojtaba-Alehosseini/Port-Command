package it.unige.portcommand.harbourmaster.financial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S17b (ADR-22): the score is four parts of 25 — money, walk-ins, safety, reputation — exactly as
 * {@code docs/TREE_ANALYSIS_DECISIONS.md} ("Final score formula") states it.
 */
class DayScoreTest {

    private static final double EPS = 1e-9;

    private static DayScore score(double net, double target, int deals, int resolved, int unsafe,
                                  int blocked, double reputationChange) {
        return new DayScore(1, net, target, deals, resolved, unsafe, blocked, reputationChange);
    }

    @ParameterizedTest(name = "net {0} against target {1} = {2} money points")
    @CsvSource({
            "0, 5000, 0",
            "2500, 5000, 12.5",
            "5000, 5000, 25",
            "9000, 5000, 25",      // a target is met once; twice the target is still 25
            "-1183, 5000, 0",      // a losing day is 0, not negative
            "1750, 3500, 12.5",    // the scenario's own target is the yardstick
    })
    void moneyIsTheNetAgainstTheTarget(double net, double target, double expected) {
        assertEquals(expected, score(net, target, 0, 0, 0, 0, 0).moneyPoints(), EPS);
    }

    @Test
    void withNoTargetADayThatLostNothingHasMetIt() {
        assertEquals(25.0, score(0, 0, 0, 0, 0, 0, 0).moneyPoints(), EPS);
        assertEquals(0.0, score(-1, 0, 0, 0, 0, 0, 0).moneyPoints(), EPS);
    }

    @ParameterizedTest(name = "{0} deal(s) of {1} ended talk(s) = {2} walk-in points")
    @CsvSource({
            "0, 0, 25",     // nobody's talk has ended: nothing was lost
            "3, 3, 25",
            "2, 3, 16.666666666666668",
            "1, 4, 6.25",
            "0, 2, 0",
    })
    void walkInsAreDealsOutOfTalksThatEnded(int deals, int resolved, double expected) {
        assertEquals(expected, score(0, 5000, deals, resolved, 0, 0, 0).walkInPoints(), EPS);
    }

    @ParameterizedTest(name = "{0} unsafe move(s) = {1} safety points")
    @CsvSource({"0, 25", "1, 20", "3, 10", "5, 0", "9, 0"})
    void safetyLosesFivePointsForEveryUnsafeMoveCarriedOut(int unsafe, double expected) {
        assertEquals(expected, score(0, 5000, 0, 0, unsafe, 0, 0).safetyPoints(), EPS);
    }

    /** What the gate refused is evidence that it works, not a fault of the player's. */
    @Test
    void aBlockedMoveCostsNothing() {
        assertEquals(25.0, score(0, 5000, 0, 0, 0, 7, 0).safetyPoints(), EPS);
    }

    @ParameterizedTest(name = "reputation {0} today = {1} points")
    @CsvSource({
            "0, 12.5",      // an unchanged reputation is the middle, whatever its level
            "1, 15",
            "5, 25",
            "8, 25",
            "-2, 7.5",
            "-5, 0",
            "-9, 0",
    })
    void reputationIsTodaysChangeAroundTheMiddle(double change, double expected) {
        assertEquals(expected, score(0, 5000, 0, 0, 0, 0, change).reputationPoints(), EPS);
    }

    @Test
    void theScoreIsTheFourPartsAddedUpAndShownAsAWholeNumber() {
        DayScore day = score(3_640, 5_000, 2, 3, 0, 1, 1);

        assertEquals(18.2, day.moneyPoints(), EPS);
        assertEquals(18.2 + 25.0 * 2 / 3 + 25.0 + 15.0, day.totalPoints(), EPS);
        assertEquals(75, day.total());
    }

    @Test
    void aPerfectDayIsAHundredAndTheWorstIsZero() {
        assertEquals(100, score(6_000, 5_000, 4, 4, 0, 0, 5).total());
        assertEquals(0, score(-500, 5_000, 0, 3, 5, 0, -6).total());
    }

    /** A fresh day: nothing earned, nobody lost, nothing unsafe, reputation where it was. */
    @Test
    void aDayOnWhichNothingHasHappenedYetStandsAtSixtyThree() {
        assertEquals(62.5, score(0, 5_000, 0, 0, 0, 0, 0).totalPoints(), EPS);
        assertEquals(63, score(0, 5_000, 0, 0, 0, 0, 0).total());
    }

    @Test
    void impossibleCountersAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> score(0, 5000, 3, 2, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> score(0, 5000, 0, 0, -1, 0, 0));
    }
}
