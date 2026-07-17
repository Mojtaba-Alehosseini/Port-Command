package it.unige.portcommand.harbourmaster.financial;

import it.unige.portcommand.ontology.Deal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ReputationRulesTest {

    private static final double EPS = 1e-9;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "DEAL,               1.0",
        "WITHDRAW_PRICE,    -2.0",
        "WITHDRAW_DURATION, -2.0", // 19b: same failure shape as a price withdrawal, same penalty
        "TIMEOUT,           -3.0",
        "PLAYER_REFUSED,     0.0"
    })
    void tableMatchesTheCanonicalDeltas(Deal.Outcome outcome, double expected) {
        assertEquals(expected, ReputationRules.deltaFor(outcome), EPS);
    }

    /** The shipped {@code defaults.json} must agree with the hard-coded canonical fallback. */
    @Test
    void defaultsJsonMatchesTheHardCodedTable() {
        assertEquals(ReputationRules.defaults(), ReputationRules.table());
    }

    /** Total by construction — a new {@link Deal.Outcome} cannot slip through unpriced. */
    @ParameterizedTest
    @EnumSource(Deal.Outcome.class)
    void everyOutcomeHasADelta(Deal.Outcome outcome) {
        assertDoesNotThrow(() -> ReputationRules.deltaFor(outcome));
    }

    /**
     * The reconciliation that pinned this table, re-derived. {@code docs/DEMO_SCRIPT_DRAFT.md}
     * shows Day 1 as "50 (start) → 51 (end, +1 net)" over 4 served + 1 over-priced withdrawal.
     * That only closes at +1 if the three WALK-IN deals earn +1 each and the CONTRACTED vessel
     * earns nothing — 4 deals would give +2 and the demo would read 52. This test is why
     * {@code LedgerCoordinator} wires reputation to {@code DealClosedEvent} (walk-in only) and not
     * to {@code ContractedFeeEarnedEvent}; if it ever fails, that wiring decision is being
     * contradicted and the demo transcript needs re-reading, not this test relaxing.
     */
    @Test
    void demoTranscriptDayOneArithmeticClosesAtPlusOne() {
        double walkInDeals = 3 * ReputationRules.deltaFor(Deal.Outcome.DEAL);
        double withdrawal = ReputationRules.deltaFor(Deal.Outcome.WITHDRAW_PRICE);

        assertEquals(1.0, walkInDeals + withdrawal, EPS, "demo: 50 -> 51, +1 net");
        assertEquals(51.0, 50.0 + walkInDeals + withdrawal, EPS);
    }

    /** The counter-case that makes the test above load-bearing rather than a coincidence. */
    @Test
    void countingTheContractedVesselWouldContradictTheDemo() {
        double fourDeals = 4 * ReputationRules.deltaFor(Deal.Outcome.DEAL)
                + ReputationRules.deltaFor(Deal.Outcome.WITHDRAW_PRICE);

        assertEquals(2.0, fourDeals, EPS, "would land the demo on 52, not the 51 it prints");
    }

    @Test
    void tableIsUnmodifiable() {
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> ReputationRules.table().put(Deal.Outcome.DEAL, 99.0));
    }
}
