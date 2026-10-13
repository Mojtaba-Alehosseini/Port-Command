package it.unige.portcommand.harbourmaster.financial;

import java.util.Map;

import it.unige.portcommand.core.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17b (ADR-22, decisions E3/E4/E8): a day pays for the hours it has, hour by hour, and is measured
 * against the session's own target.
 */
class SessionTermsTest {

    private static final double EPS = 1e-9;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    /** Every packaged scenario opens on day 1 at 08:00. */
    private static final long EIGHT_AM = 8 * HOUR;

    private final SessionTerms freePlay = SessionTerms.freePlay();
    private final SessionTerms tutorial = SessionTerms.scenario("Tutorial — Calm Morning", 3_500.0, EIGHT_AM);

    // ---- the fixed cost, by the hour ----

    @Test
    void aWholeDayIsChargedOneTwentyFourthForEveryHourThatHasPassed() {
        assertEquals(0.0, freePlay.fixedCostSoFar(1, 0L), EPS, "midnight: nothing yet");
        assertEquals(0.0, freePlay.fixedCostSoFar(1, HOUR - 1), EPS, "00:59: the first hour is not over");
        assertEquals(5_200.0 / 24, freePlay.fixedCostSoFar(1, HOUR), EPS, "01:00");
        assertEquals(5_200.0 / 2, freePlay.fixedCostSoFar(1, 12 * HOUR + 1_800_000L), EPS, "12:30 = 12 hours");
        assertEquals(5_200.0 * 23 / 24, freePlay.fixedCostSoFar(1, DAY - 1), EPS, "23:59");
        assertEquals(5_200.0, freePlay.fixedCostSoFar(1, DAY), EPS, "the day is over: all of it");
        assertEquals(5_200.0, freePlay.fixedCostForDay(1), EPS);
        assertEquals(24, freePlay.chargeableHours(1));
    }

    /** Decision E3: "a day starting at 08:00 pays 16/24". */
    @Test
    void aDayThatOpensAtEightOwesSixteenTwentyFourths() {
        assertEquals(16, tutorial.chargeableHours(1));
        assertEquals(5_200.0 * 16 / 24, tutorial.fixedCostForDay(1), EPS);
        assertEquals(0.0, tutorial.fixedCostSoFar(1, EIGHT_AM), EPS, "the opening instant");
        assertEquals(5_200.0 / 24, tutorial.fixedCostSoFar(1, 9 * HOUR), EPS, "09:00: one hour played");
        assertEquals(5_200.0 * 15 / 24, tutorial.fixedCostSoFar(1, DAY - 1), EPS, "23:59");
        assertEquals(5_200.0 * 16 / 24, tutorial.fixedCostSoFar(1, DAY), EPS, "midnight");
    }

    @Test
    void everyLaterDayOfAScenarioSessionIsAWholeDay() {
        assertEquals(24, tutorial.chargeableHours(2));
        assertEquals(5_200.0, tutorial.fixedCostForDay(2), EPS);
        assertEquals(5_200.0 / 24, tutorial.fixedCostSoFar(2, DAY + HOUR), EPS);
    }

    @Test
    void aDayThatHasNotBegunHasBeenChargedNothing() {
        assertEquals(0.0, freePlay.fixedCostSoFar(2, DAY - 1), EPS);
        assertEquals(0, tutorial.hoursCharged(1, 3 * HOUR), "before the scenario opened");
    }

    /** The settlement books the same five lines, cut to the day's hours - never re-typed. */
    @Test
    void theBreakdownOfAShortDayIsEachCanonicalLineInProportion() {
        Map<String, Double> lines = tutorial.fixedBreakdownForDay(1);

        assertEquals(ExpenseRules.dailyFixedBreakdown().keySet(), lines.keySet());
        assertEquals(400.0, lines.get(ExpenseRules.SOURCE_SALARIES), EPS, "600 x 16/24");
        assertEquals(800.0, lines.get(ExpenseRules.SOURCE_BERTH_MAINTENANCE), EPS, "1200 x 16/24");
        assertEquals(1_000.0, lines.get(ExpenseRules.SOURCE_INSURANCE), EPS, "1500 x 16/24");
        assertEquals(5_200.0 * 16 / 24, lines.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-6);
        assertEquals(ExpenseRules.dailyFixedBreakdown(), tutorial.fixedBreakdownForDay(2),
                "a whole day is the canonical breakdown itself");
        assertEquals(ExpenseRules.dailyFixedBreakdown(), freePlay.fixedBreakdownForDay(1));
    }

    // ---- the scenario's one day, and its target ----

    @Test
    void aScenarioIsLiveOnItsOwnDayOnly() {
        assertTrue(tutorial.scenarioLive(1));
        assertEquals("Tutorial — Calm Morning", tutorial.scenarioName(1));
        assertFalse(tutorial.scenarioLive(2), "after its first midnight it is free play");
        assertNull(tutorial.scenarioName(2));
        assertFalse(freePlay.scenarioLive(1));
        assertNull(freePlay.scenarioName(1));
    }

    /** Decision E4: the scenario's target is the session's, never written into Settings. */
    @Test
    void theTargetIsTheScenariosOnItsDayAndThePlayersOwnOtherwise() {
        double playersOwn = Settings.load().dailyTargetEur();

        assertEquals(3_500.0, tutorial.targetEur(1), EPS);
        assertEquals(playersOwn, tutorial.targetEur(2), EPS, "carried on in free play");
        assertEquals(playersOwn, freePlay.targetEur(1), EPS);
        assertEquals(playersOwn, Settings.load().dailyTargetEur(), EPS, "and Settings was not touched");
    }

    @Test
    void aScenarioNeedsANameAndAStart() {
        assertThrows(IllegalArgumentException.class, () -> SessionTerms.scenario(" ", 1_000.0, 0L));
        assertThrows(IllegalArgumentException.class, () -> SessionTerms.scenario("x", 1_000.0, -1L));
    }
}
