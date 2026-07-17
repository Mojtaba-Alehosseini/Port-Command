package it.unige.portcommand.harbourmaster.financial;

import it.unige.portcommand.harbourmaster.IncomeEvent;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncomeRulesTest {

    private static final long DAY_MILLIS = 86_400_000L;
    private static final double EPS = 1e-9;

    // ---- §7.5 fee bands (the canonical table) ----

    /**
     * PROJECT_DEFINITION §7.5, byte-for-byte. This is the single source of truth that
     * {@code vessel_templates.json} must also match (INVARIANTS: "One economy table") — if this
     * test and {@code VesselTemplatesTest} ever disagree, the economy has drifted.
     */
    @ParameterizedTest(name = "{0} band = [{1}, {2}]")
    @CsvSource({
        "cargo_vessel,     1400, 2200",
        "container_vessel, 1800, 3500",
        "tanker,           4000, 8000",
        "ferry,            1100, 2000",
        "cruise_ship,      3200, 5500"
    })
    void berthFeeRangeMatchesTheCanonicalSevenPointFiveTable(String type, double lo, double hi) {
        IncomeRules.PriceRange range = IncomeRules.berthFeeRange("berth_1", type);

        assertEquals(lo, range.lo(), EPS);
        assertEquals(hi, range.hi(), EPS);
        assertEquals((lo + hi) / 2.0, range.midpoint(), EPS);
    }

    @Test
    void berthFeeRangeRejectsAnUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> IncomeRules.berthFeeRange("berth_1", "pleasure"));
    }

    /** Every berth shares the type's band — §7.5 has no per-berth column to honour. */
    @Test
    void berthFeeRangeIsBerthIndependent() {
        assertEquals(IncomeRules.berthFeeRange("berth_1", "tanker"),
                IncomeRules.berthFeeRange("berth_4", "tanker"));
    }

    // ---- berthBase and the 19b seam ----

    @ParameterizedTest(name = "berthBase(_, {0}h, {1}) = {1}")
    @CsvSource({"4, 2200.0", "8, 5200.0", "12, 1100.0"})
    void berthBasePassesTheSettledPriceThrough(int hours, double dealPrice) {
        assertEquals(dealPrice, IncomeRules.berthBase("tanker", hours, dealPrice), EPS);
    }

    /**
     * The task-19b seam, pinned: fees are per-deal FLAT today, so hours does not move the fee.
     * When 19b makes duration negotiable this test is the one that must change — and it should be
     * the ONLY one, which is the point of keeping the interaction in this single method.
     */
    @Test
    void berthBaseIgnoresHoursToday_theNineteenBSeam() {
        assertEquals(IncomeRules.berthBase("tanker", 1, 5_000.0),
                IncomeRules.berthBase("tanker", 24, 5_000.0), EPS,
                "fees are per-deal flat until task 19b adds fee-per-hours HERE");
    }

    @Test
    void berthBaseAcceptsANullTypeBecauseNeitherLiveEventCarriesOne() {
        assertEquals(2_200.0, IncomeRules.berthBase(null, 4, 2_200.0), EPS);
    }

    @Test
    void berthBaseStillRejectsAWrongTypeWhenOneIsSupplied() {
        assertThrows(IllegalArgumentException.class, () -> IncomeRules.berthBase("pleasure", 4, 2_200.0));
    }

    @Test
    void berthBaseRejectsANegativePrice() {
        assertThrows(IllegalArgumentException.class, () -> IncomeRules.berthBase("tanker", 4, -1.0));
    }

    // ---- surcharges ----

    @ParameterizedTest(name = "extraTugSurcharge({0}) = {1}")
    @CsvSource({"0, 0.0", "1, 0.0", "2, 500.0", "3, 1000.0", "4, 1500.0"})
    void extraTugSurchargeChargesFiveHundredPerTugBeyondTheFirst(int tugs, double expected) {
        assertEquals(expected, IncomeRules.extraTugSurcharge(tugs), EPS);
    }

    @Test
    void extraTugSurchargeRejectsANegativeCount() {
        assertThrows(IllegalArgumentException.class, () -> IncomeRules.extraTugSurcharge(-1));
    }

    @Test
    void flatSurchargesAreTheCanonicalFigures() {
        assertEquals(500.0, IncomeRules.hazmatSurcharge(true), EPS);
        assertEquals(0.0, IncomeRules.hazmatSurcharge(false), EPS);
        assertEquals(300.0, IncomeRules.pilotService(true), EPS);
        assertEquals(0.0, IncomeRules.pilotService(false), EPS);
        assertEquals(150.0, IncomeRules.waterAndWaste(), EPS);
        assertEquals(1_234.5, IncomeRules.bunkerFuel(1_234.5), EPS);
    }

    @ParameterizedTest(name = "premiumSurcharge(1000, rep={0}) = {1}")
    @CsvSource({
        "0.0,   0.0",
        "79.0,  0.0",
        "79.99, 0.0",
        "80.0,  150.0",   // the threshold is inclusive
        "100.0, 150.0"
    })
    void premiumSurchargeUnlocksAtReputationEighty(double reputation, double expected) {
        assertEquals(expected, IncomeRules.premiumSurcharge(1_000.0, reputation), EPS);
    }

    // ---- aggregateForDay ----

    @Test
    void aggregateForDaySumsOnlyThatDaysIncome() {
        WalletLedger ledger = new WalletLedger(0.0, new EventBus());
        ledger.recordIncome(new IncomeEvent(2_200.0, IncomeRules.SOURCE_BERTH_BASE, "V1", 0L));
        ledger.recordIncome(new IncomeEvent(500.0, IncomeRules.SOURCE_EXTRA_TUG, "V1", 5_000L));
        ledger.recordIncome(new IncomeEvent(1_800.0, IncomeRules.SOURCE_BERTH_BASE, "V2", DAY_MILLIS));

        assertEquals(2_700.0, IncomeRules.aggregateForDay(ledger, 1), EPS);
        assertEquals(1_800.0, IncomeRules.aggregateForDay(ledger, 2), EPS);
        assertEquals(0.0, IncomeRules.aggregateForDay(ledger, 3), EPS);
    }

    /** The last millisecond of a day belongs to that day, not the next one. */
    @Test
    void aggregateForDayBucketsTheDayBoundaryCorrectly() {
        WalletLedger ledger = new WalletLedger(0.0, new EventBus());
        ledger.recordIncome(new IncomeEvent(1.0, IncomeRules.SOURCE_BERTH_BASE, "V1", DAY_MILLIS - 1));
        ledger.recordIncome(new IncomeEvent(10.0, IncomeRules.SOURCE_BERTH_BASE, "V2", DAY_MILLIS));

        assertEquals(1.0, IncomeRules.aggregateForDay(ledger, 1), EPS);
        assertEquals(10.0, IncomeRules.aggregateForDay(ledger, 2), EPS);
    }

    @Test
    void aggregateForDayDoesNotMutateTheLedger() {
        WalletLedger ledger = new WalletLedger(500.0, new EventBus());
        ledger.recordIncome(new IncomeEvent(2_200.0, IncomeRules.SOURCE_BERTH_BASE, "V1", 0L));
        double afterCredit = ledger.balance();

        IncomeRules.aggregateForDay(ledger, 1);
        IncomeRules.aggregateForDay(ledger, 1);

        assertEquals(afterCredit, ledger.balance(), EPS, "read-only aggregation, called twice");
        assertEquals(1, ledger.incomeHistory().size());
    }
}
