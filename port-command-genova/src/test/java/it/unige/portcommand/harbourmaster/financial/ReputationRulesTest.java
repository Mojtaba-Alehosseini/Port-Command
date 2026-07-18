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
     * Task 24 (checkpoint #5, Moji's balance call): {@code withdrawalDelta} splits ONLY TIMEOUT
     * by engagement — a never-engaged timeout is the gentler −1, everything else is its canonical
     * table value regardless of the bit.
     */
    @ParameterizedTest(name = "{0} engaged={1} -> {2}")
    @CsvSource({
        // TIMEOUT is the only outcome the engagement bit moves.
        "TIMEOUT,           true,  -3.0",
        "TIMEOUT,           false, -1.0",
        // Everything else ignores it (both columns land on the table value).
        "WITHDRAW_PRICE,    true,  -2.0",
        "WITHDRAW_PRICE,    false, -2.0",
        "WITHDRAW_DURATION, false, -2.0",
        "PLAYER_REFUSED,    false,  0.0",
        "PLAYER_REFUSED,    true,   0.0"
    })
    void withdrawalDeltaSplitsOnlyTimeoutByEngagement(Deal.Outcome outcome, boolean engaged, double expected) {
        assertEquals(expected, ReputationRules.withdrawalDelta(outcome, engaged), EPS);
    }

    @Test
    void theUnengagedTimeoutScalarIsMinusOneAndGentlerThanTheEngagedRow() {
        assertEquals(-1.0, ReputationRules.timeoutUnengagedDelta(), EPS);
        assertEquals(-1.0, ReputationRules.withdrawalDelta(Deal.Outcome.TIMEOUT, false), EPS);
        org.junit.jupiter.api.Assertions.assertTrue(
                ReputationRules.timeoutUnengagedDelta() > ReputationRules.deltaFor(Deal.Outcome.TIMEOUT),
                "an ignored walk-in must cost LESS than one engaged and abandoned");
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

    /**
     * Task 23 (checkpoint-#6 balance call, 2026-07-18): the daily cap on cumulative
     * unengaged-timeout loss is the −5.0 {@code reputation}-block scalar, and the
     * accumulator clamps to it exactly — including a partial last step — then resets.
     */
    @Test
    void unengagedDailyCapScalarIsMinusFiveAndTheAccumulatorClampsToIt() {
        assertEquals(-5.0, ReputationRules.timeoutUnengagedDailyCap(), EPS);

        ReputationRules.UnengagedTimeoutDailyCap cap = new ReputationRules.UnengagedTimeoutDailyCap();
        double applied = 0.0;
        for (int i = 0; i < 10; i++) {
            applied += cap.clamp(ReputationRules.timeoutUnengagedDelta()); // 10 × −1
        }
        assertEquals(-5.0, applied, EPS, "10 unengaged timeouts apply exactly the cap");
        assertEquals(0.0, cap.clamp(-1.0), EPS, "budget spent — further penalties clamp to 0");

        cap.resetForNewDay();
        assertEquals(-1.0, cap.clamp(-1.0), EPS, "rollover restores the full budget");

        ReputationRules.UnengagedTimeoutDailyCap partial = new ReputationRules.UnengagedTimeoutDailyCap();
        assertEquals(-4.5, partial.clamp(-4.5), EPS);
        assertEquals(-0.5, partial.clamp(-1.0), EPS, "the last step is clamped partially, not dropped");
    }
}
