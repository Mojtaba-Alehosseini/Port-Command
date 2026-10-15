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

    /** S19 (playtest 5, PT42): the Hint's tooltip shows the cost's parts - and they are the
     * marginal cost's own, for every type: the display cannot drift from the figure. */
    @Test
    void theCostPartsAreTheMarginalCostsOwn() {
        for (String type : java.util.List.of("tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship")) {
            for (int hours : new int[] {1, 6, 17}) {
                assertEquals(ExpenseRules.marginalCost(type, "general_cargo", hours, 30000),
                        ExpenseRules.marginalCostParts(type, hours).total(), 1e-9, type + " " + hours + "h");
            }
        }
        ExpenseRules.CostParts tanker = ExpenseRules.marginalCostParts("tanker", 6);
        assertEquals(2, tanker.tugs());
        assertEquals(350.0, tanker.perTug(), 0.0);
        assertEquals(100.0, tanker.customs(), 0.0);
        assertEquals(6, tanker.hours());
        assertEquals(50.0, tanker.perHour(), 0.0);
        assertEquals(0, ExpenseRules.marginalCostParts("ferry", 4).tugs(), "a ferry takes no tug");
    }

    private static final long DAY_MILLIS = 86_400_000L;
    private static final double EPS = 1e-9;

    // ---- the €5200 invariant (design rule 7) ----

    /**
     * The invariant, computed from its five named components rather than asserted against a
     * literal 5200 — a test that only says {@code assertEquals(5200, dailyFixed())} passes just as
     * happily if someone rewrites the breakdown into different numbers that still total 5200,
     * which is exactly the drift design rule 7 is guarding.
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
        assertEquals(5_200.0, ExpenseRules.dailyFixed(), EPS, "design rule 7 — never 5050");
    }

    @Test
    void dailyFixedIsNeverTheOldFiftyFiftyBug() {
        assertTrue(ExpenseRules.dailyFixed() != 5_050.0,
                "5050 is the pre-v1.1 bug design rule 7 forbids on sight");
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
    void customsIsTheCanonicalFlatFigure() {
        assertEquals(100.0, ExpenseRules.customsClearance(), EPS);
    }

    /** S17b (E5): the incident fine had no producer — there is no incident event — and is gone. */
    @Test
    void theIncidentFineIsGone() {
        java.util.Set<String> sources = new java.util.TreeSet<>();
        for (java.lang.reflect.Field f : ExpenseRules.class.getDeclaredFields()) {
            if (f.getName().startsWith("SOURCE_")) {
                sources.add(f.getName());
            }
        }
        assertEquals(java.util.Set.of("SOURCE_TUG_JOB", "SOURCE_CUSTOMS", "SOURCE_SALARIES",
                "SOURCE_BERTH_MAINTENANCE", "SOURCE_TUG_IDLE_LEASE", "SOURCE_INSURANCE",
                "SOURCE_UTILITIES"), sources);
        for (java.lang.reflect.Method m : ExpenseRules.class.getDeclaredMethods()) {
            org.junit.jupiter.api.Assertions.assertNotEquals("incidentFine", m.getName());
        }
    }

    /**
     * Pins the §5.2 canonical tug counts through the formula: {@code tugs*350 + 100 customs share
     * + hours*50 berth occupancy}, at hours=4 → {@code tugs*350 + 300}. Ferry's 0 tugs (own
     * propulsion) is the row that would break loudest if §5.2 ever drifted.
     *
     * <p>S11 (logic hunt, L1): cargo corrected 1 → 2. The old row pinned §5.2's abstract
     * "cargo 1-2" lower bound, but R11 gives 2 for every tonnage the cargo templates can draw
     * ([20k, 50k]) — the pin was locking in a €350-short EV. PrologQueriesTest cross-checks
     * this table against R11 itself.
     */
    @ParameterizedTest(name = "marginalCost({0}) = {2} ({1} tugs)")
    @CsvSource({
        "tanker,           2, 1000.0",
        "container_vessel, 2, 1000.0",
        "cargo_vessel,     2, 1000.0",
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
