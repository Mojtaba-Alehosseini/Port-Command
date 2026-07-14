package it.unige.portcommand.assistant;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.prolog.PrologEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven coverage of the v1.1 EV-based recommendation heuristic (planning/10 §10.2).
 * Berth/vessel fixtures reuse the same known-compatible/incompatible pairs
 * {@code PrologQueriesTest} already proves (tanker+berth_1 fits; tanker+berth_4 fails on
 * draft) rather than re-deriving Prolog facts independently.
 */
class RecommendationAlgorithmTest {

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    private static WalkInDialogueSnapshot tankerAtBerth1(double lastVesselOffer, int roundsUsed,
                                                          double timeUsedSec) {
        return new WalkInDialogueSnapshot("tanker_1", "tanker", 14.0, 140.0, 20000, "liquid_bulk",
                "berth_1", 6, lastVesselOffer, 0.0, roundsUsed, timeUsedSec, false);
    }

    @Test
    void round1OfferBelowMarginalCostStillAvoidsRejectSilentDueToUrgencyFloor() {
        // Deliberately BELOW marginal cost (1100 for a 6h tanker call: 2 tugs*350 + 100 customs +
        // 6*50 berth-share) so accept.ev = 1000-1100 = -100 cannot dominate on its own -- this is
        // what makes the urgency floor load-bearing (an at-market offer's accept.ev is already
        // positive regardless of urgency, so it would pass even without the floor). Without the
        // v1.1 fix, round 1's raw urgency is exactly 0.0, zeroing p_accept for every counter and
        // driving its EV to exactly the -500 withdrawal penalty, so reject_silent (ev=0) wins --
        // the literal bug: "the Assistant would recommend reject silently on every first Hint
        // click." With the 0.4 floor, p_accept ~ 0.35 and counter(+10%) (clamped up to the
        // fallback band's 4000 floor) scores ev ~ +698, beating both accept and reject_silent.
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        Recommendation rec = algorithm.run(tankerAtBerth1(1000.0, 0, 0.0));
        assertNotEquals(RecommendationCandidate.REJECT_SILENT, rec.action(),
                "round-1 offer below marginal cost must not collapse to reject_silent (the urgency-floor bug)");
        assertEquals(RecommendationCandidate.COUNTER, rec.action());
        assertEquals(4000.0, rec.price(), 1e-9);
        assertTrue(rec.ev() > 0.0, "counter's EV must beat reject_silent's 0.0 thanks to the urgency floor");
    }

    @Test
    void deterministicGivenSameInputs() {
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        WalkInDialogueSnapshot snapshot = tankerAtBerth1(6000.0, 1, 60.0);
        Recommendation first = algorithm.run(snapshot);
        Recommendation second = algorithm.run(snapshot);
        assertEquals(first.action(), second.action());
        assertEquals(first.price(), second.price(), 1e-9);
        assertEquals(first.ev(), second.ev(), 1e-9);
        assertEquals(first.pAccept(), second.pAccept(), 1e-9);
    }

    @Test
    void incompatibleBerthOnlyLeavesRejectSilent() {
        // Same fixture PrologQueriesTest proves incompatible: tanker draft 14 exceeds berth_4's
        // max draft (the ferry berth) — no candidate that "involves" this berth may survive.
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot("tanker_1", "tanker", 14.0, 140.0, 20000,
                "liquid_bulk", "berth_4", 6, 6000.0, 0.0, 1, 0.0, false);
        Recommendation rec = algorithm.run(snapshot);
        assertEquals(RecommendationCandidate.REJECT_SILENT, rec.action());
        assertEquals(0.0, rec.price());
        assertEquals("incompatible", rec.prologStatus());
    }

    @Test
    void acceptBeatsAnOverGreedyCounterNearTopOfBand() {
        // Hand-verified argmax table case: offer 1900 is already near the top of the cargo_vessel
        // fair-price band ([1400, 2200], centre 1800); marginal cost is 700 (1 tug + customs +
        // 5h berth share). accept.ev = 1900-700 = 1200. counter(+10%) clamps to 2090 with
        // p_accept~0.19 at MAXIMUM urgency (round 4, fully elapsed, threatened) -> ev ~ -141;
        // counter(+20%) clamps to the band edge 2200 with p_accept~0.12 -> ev ~ -262. Even at
        // max urgency, gambling on a counter is dominated by the -500 withdrawal-penalty risk.
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot("cargo_1", "cargo_vessel", 8.0, 150.0, 30000,
                "general_cargo", "berth_3", 5, 1900.0, 0.0,
                UrgencyCalculator.DEFAULT_ROUND_LIMIT, UrgencyCalculator.DEFAULT_TIME_LIMIT_SEC, true);
        Recommendation rec = algorithm.run(snapshot);
        assertEquals(RecommendationCandidate.ACCEPT, rec.action());
        assertEquals(1900.0, rec.price(), 1e-9);
        assertEquals(1200.0, rec.ev(), 1e-6);
    }

    @Test
    void counterBeatsAcceptWhenOfferBarelyCoversMarginalCost() {
        // Mirror case: offer 750 barely clears marginal cost (700), so accept.ev is only 50 —
        // while the band centre (1800) is far above the offer, so a counter clamped up to the
        // band floor (1400) has p_accept~0.51 at round 3's urgency (0.58) -> ev ~113, beating
        // accept outright. counter(+10%) and counter(+20%) both clamp to the same 1400 floor
        // here (both raw values undercut it), so they tie; the algorithm picks the first (+10%).
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot("cargo_2", "cargo_vessel", 8.0, 150.0, 30000,
                "general_cargo", "berth_3", 5, 750.0, 0.0, 3, 0.0, false);
        Recommendation rec = algorithm.run(snapshot);
        assertEquals(RecommendationCandidate.COUNTER, rec.action());
        assertEquals(1400.0, rec.price(), 1e-9);
        assertTrue(rec.ev() > 50.0, "counter's EV must beat accept's EV (50.0); got " + rec.ev());
    }

    @Test
    void lowSampleCountUsesIncomeRulesFallback() {
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        Recommendation rec = algorithm.run(tankerAtBerth1(6000.0, 1, 60.0));
        assertTrue(rec.marketStats().lowConfidence(), "empty market history must be low-confidence");
        assertEquals(0, rec.marketStats().sampleCount());
    }

    @Test
    void chosenCandidateNeverHasLowerEvThanRejectSilent() {
        // reject_silent (ev=0) is always a candidate; the argmax must never pick something worse.
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        Recommendation rec = algorithm.run(tankerAtBerth1(4500.0, 2, 120.0));
        assertTrue(rec.ev() >= 0.0, "chosen action's EV must be >= reject_silent's 0.0; got " + rec.ev());
    }
}
