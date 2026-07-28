package it.unige.portcommand.harbourmaster.financial;

import java.util.List;
import java.util.Map;

import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpenseRulesTest {

    private static final long DAY_MILLIS = 86_400_000L;
    private static final double EPS = 1e-9;

    // ---- the €5200 invariant (CLAUDE.md rule 7) ----

    /**
     * The invariant, computed from its five named components rather than asserted against a
     * literal 5200 — a test that only says {@code assertEquals(5200, dailyFixed())} passes just as
     * happily if someone rewrites the breakdown into different numbers that still total 5200,
     * which is exactly the drift CLAUDE.md rule 7 is guarding.
     */
    @Test
    void dailyFixedIsTheSumOfItsFiveCanonicalComponents() {
        Map<String, Double> breakdown = ExpenseRules.dailyFixedBreakdown();

        assertEquals(5, breakdown.size(), "the fixed cost has exactly five lines");
        assertEquals(150.0 * 4, breakdown.get("salaries"), EPS, "4 staff x EUR150");
        assertEquals(1_200.0, breakdown.get("berth_maintenance"), EPS);
        assertEquals(400.0 * 4, breakdown.get("tug_idle_lease"), EPS, "4 tugs x EUR400");
        assertEquals(1_500.0, breakdown.get("insurance"), EPS);
        assertEquals(300.0, breakdown.get("utilities"), EPS);

        double sum = 150.0 * 4 + 1_200.0 + 400.0 * 4 + 1_500.0 + 300.0;
        assertEquals(sum, ExpenseRules.dailyFixed(), EPS);
        assertEquals(5_200.0, ExpenseRules.dailyFixed(), EPS, "CLAUDE.md rule 7 — never 5050");
    }

    @Test
    void dailyFixedIsNeverTheOldFiftyFiftyBug() {
        assertTrue(ExpenseRules.dailyFixed() != 5_050.0,
                "5050 is the pre-v1.1 bug CLAUDE.md rule 7 forbids on sight");
    }

    @Test
    void dailyFixedBreakdownKeepsCanonicalOrderAndIsUnmodifiable() {
        assertEquals(List.of("salaries", "berth_maintenance", "tug_idle_lease", "insurance", "utilities"),
                List.copyOf(ExpenseRules.dailyFixedBreakdown().keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> ExpenseRules.dailyFixedBreakdown().put("bribes", 1.0));
    }

    // ---- per-source rules ----

    @ParameterizedTest(name = "tugJob({0}) = {0}")
    @CsvSource({"350.0", "425.5", "600.0", "0.0"})
    void tugJobPassesTheWinningBidThrough(double bid) {
        assertEquals(bid, ExpenseRules.tugJob(bid), EPS);
    }

    @Test
    void tugJobRejectsANegativeBid() {
        assertThrows(IllegalArgumentException.class, () -> ExpenseRules.tugJob(-1.0));
    }

    @Test
    void customsAndIncidentAreTheCanonicalFlatFigures() {
        assertEquals(100.0, ExpenseRules.customsClearance(), EPS);
        assertEquals(2_000.0, ExpenseRules.incidentFine(), EPS);
    }

    /**
     * Pins the §5.2 canonical tug counts through the formula: {@code tugs*350 + 100 customs share
     * + hours*50 berth occupancy}, at hours=4 → {@code tugs*350 + 300}. Ferry's 0 tugs (own
     * propulsion) is the row that would break loudest if §5.2 ever drifted.
     */
    @ParameterizedTest(name = "marginalCost({0}) = {2} ({1} tugs)")
    @CsvSource({
        "tanker,           2, 1000.0",
        "container_vessel, 2, 1000.0",
        "cargo_vessel,     1,  650.0",
        "cruise_ship,      2, 1000.0",
        "ferry,            0,  300.0"
    })
    void marginalCostUsesTheCanonicalTugCountPerType(String type, int expectedTugs, double expected) {
        assertEquals(expectedTugs * 350.0 + 100.0 + 4 * 50.0, expected, EPS, "the row's own arithmetic");
        assertEquals(expected, ExpenseRules.marginalCost(type, "general_cargo", 4, 20_000), EPS);
    }

    @Test
    void marginalCostRejectsAnUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> ExpenseRules.marginalCost("pleasure", "general_cargo", 4, 1_000));
    }

    // ---- variableForDay ----

    @Test
    void variableForDaySumsOnlyThatDaysVariableExpenses() {
        WalletLedger ledger = new WalletLedger(0.0, new EventBus());
        ledger.recordExpense(new ExpenseEvent(400.0, ExpenseRules.SOURCE_TUG_JOB, "V1", 0L));
        ledger.recordExpense(new ExpenseEvent(100.0, ExpenseRules.SOURCE_CUSTOMS, "V1", 1_000L));
        ledger.recordExpense(new ExpenseEvent(999.0, ExpenseRules.SOURCE_TUG_JOB, "V2", DAY_MILLIS));

        assertEquals(500.0, ExpenseRules.variableForDay(ledger, 1), EPS, "day 1 only");
        assertEquals(999.0, ExpenseRules.variableForDay(ledger, 2), EPS, "day 2 only");
        assertEquals(0.0, ExpenseRules.variableForDay(ledger, 3), EPS, "a day with nothing in it");
    }

    /**
     * The EOD double-charge guard. Task 24 charges the five fixed lines into this same ledger, so
     * they land in the same day's history — {@code variableForDay} must exclude them by SOURCE, not
     * by having been called first.
     */
    @Test
    void variableForDayExcludesTheFixedLinesEvenAfterTaskTwentyFourChargesThem() {
        WalletLedger ledger = new WalletLedger(0.0, new EventBus());
        ledger.recordExpense(new ExpenseEvent(400.0, ExpenseRules.SOURCE_TUG_JOB, "V1", 0L));
        ExpenseRules.dailyFixedBreakdown()
                .forEach((source, amount) -> ledger.recordExpense(new ExpenseEvent(amount, source, null, 0L)));

        assertEquals(400.0, ExpenseRules.variableForDay(ledger, 1), EPS,
                "the fixed EUR5200 must not leak into the variable total");
    }

    @Test
    void variableForDayDoesNotMutateTheLedger() {
        WalletLedger ledger = new WalletLedger(1_000.0, new EventBus());
        ledger.recordExpense(new ExpenseEvent(400.0, ExpenseRules.SOURCE_TUG_JOB, "V1", 0L));
        double afterCharge = ledger.balance();

        ExpenseRules.variableForDay(ledger, 1);
        ExpenseRules.variableForDay(ledger, 1);

        assertEquals(afterCharge, ledger.balance(), EPS, "read-only aggregation, called twice");
        assertEquals(1, ledger.expenseHistory().size());
    }
}
