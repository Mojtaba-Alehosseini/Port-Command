package it.unige.portcommand.assistant;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.core.Settings;
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

    /**
     * The round budget these fixtures' hand-verified arithmetic was worked out under. Passed
     * EXPLICITLY since S14: the shipped default went 4 -> 6, and a test that silently read
     * {@code Settings} would have had every "round 4 = last round" figure shift under it.
     */
    private static final int ROUNDS = 4;
    /** A conversation counter's total, for the fixtures that want the time term in play. */
    private static final double WINDOW = 60.0;

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
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact(), ROUNDS);
        Recommendation rec = algorithm.run(tankerAtBerth1(1000.0, 0, 0.0));
        assertNotEquals(RecommendationCandidate.REJECT_SILENT, rec.action(),
                "round-1 offer below marginal cost must not collapse to reject_silent (the urgency-floor bug)");
        assertEquals(RecommendationCandidate.COUNTER, rec.action());
        assertEquals(4000.0, rec.price(), 1e-9);
        assertTrue(rec.ev() > 0.0, "counter's EV must beat reject_silent's 0.0 thanks to the urgency floor");
    }

    /**
     * S14 (ADR-18): the urgency time term is the vessel's VISIBLE conversation counter — elapsed
     * against total, carried by the snapshot — not a Settings window. Pins the behaviour on the
     * Assistant side; the pure {@code UrgencyCalculator} cases cannot, that function is unchanged.
     */
    @Test
    void theVisibleCounterChangesTheRecommendationsUrgency() {
        // 15 elapsed real seconds is half of a 30 s counter but a twelfth of a 180 s one, so the
        // same dialogue is materially more urgent on the shorter counter. Urgency scales p_accept,
        // which scales a counter candidate's EV.
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact(), ROUNDS);
        WalkInDialogueSnapshot snapshot = tankerAtBerth1(1000.0, 1, 0.0);
        double tightEv = algorithm.run(snapshot.withTime(15.0, 30.0)).ev();
        double looseEv = algorithm.run(snapshot.withTime(15.0, 180.0)).ev();

        assertTrue(tightEv > looseEv,
                "15 s into a 30 s counter must price this counter above 15 s into a 180 s one ("
                        + tightEv + " vs " + looseEv + ") - if these are equal the time term is dead again");
    }

    /** A snapshot with no counter on it adds no time pressure — it never reads as "out of time". */
    @Test
    void aSnapshotWithoutACounterAddsNoTimePressure() {
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact(), ROUNDS);
        WalkInDialogueSnapshot noCounter = tankerAtBerth1(1000.0, 1, 45.0); // elapsed, but no total
        assertEquals(WalkInDialogueSnapshot.NO_TIME_LIMIT, noCounter.timeLimitSec());

        assertEquals(algorithm.run(noCounter.withTime(0.0, 180.0)).ev(), algorithm.run(noCounter).ev(), 1e-9,
                "no counter must price exactly like a counter that has not started running");
    }

    /**
     * The round-limit twin (2026-08-10). Until now the algorithm passed a hardcoded
     * round limit while the HarbourMaster derived its final-round flag from
     * {@code Settings.roundLimit()} — one figure, two sources.
     */
    @Test
    void theConfiguredRoundLimitChangesTheRecommendationsUrgency() {
        // Round 2 of a 2-round budget is the last round; round 2 of an 8-round budget has six
        // left. The tighter budget must price the same counter higher, exactly as the tighter
        // deadline does above — if these are equal, the injected limit is not being read.
        WalkInDialogueSnapshot snapshot = tankerAtBerth1(1000.0, 2, 0.0);
        double tightEv = new RecommendationAlgorithm(new MarketHistoryArtifact(), 2).run(snapshot).ev();
        double looseEv = new RecommendationAlgorithm(new MarketHistoryArtifact(), 8).run(snapshot).ev();

        assertTrue(tightEv > looseEv,
                "a 2-round budget must price this counter above an 8-round one (" + tightEv
                        + " vs " + looseEv + ") - if these are equal the round term is hardcoded");
    }

    /**
     * The Assistant's budget must be the same number the player is shown. {@code finalRound} in
     * {@code ForwardWalkInToPlayerBehaviour} is {@code roundLimit + 1 - roundsUsed <= 1}, so the
     * final round is {@code roundsUsed == roundLimit} — where the round-progress term is exactly
     * 1.0, a fully spent budget, at EVERY configured limit. Two dialogues each on their own last
     * round must therefore be priced identically no matter how long their budgets were.
     *
     * <p>That equality is what makes this non-vacuous: with the limit hardcoded to 4 the two sides
     * are round 2 of 4 and round 8 of 4, which are nowhere near equal.
     */
    @Test
    void theFinalRoundIsEquallyUrgentAtEveryConfiguredLimit() {
        double atFinalOfTwo = new RecommendationAlgorithm(new MarketHistoryArtifact(), 2)
                .run(tankerAtBerth1(1000.0, 2, 0.0)).ev();
        double atFinalOfEight = new RecommendationAlgorithm(new MarketHistoryArtifact(), 8)
                .run(tankerAtBerth1(1000.0, 8, 0.0)).ev();

        assertEquals(atFinalOfTwo, atFinalOfEight, 1e-9,
                "round 2 of 2 and round 8 of 8 are both a spent budget, so they must price the "
                        + "same (" + atFinalOfTwo + " vs " + atFinalOfEight + ") - if they differ, "
                        + "the algorithm is not reading the limit the player is shown");
    }

    @Test
    void theDefaultConstructorUsesTheLiveSettingsBudgets() {
        // Honest limit: on its own this cannot separate "reads Settings" from "hard-codes a number
        // that happens to equal defaults.json". What makes the group meaningful is the round-limit
        // siblings above, which prove the constructor argument is genuinely live — given that, this
        // pins the production path to the same budget the player sees.
        WalkInDialogueSnapshot snapshot = tankerAtBerth1(1000.0, 1, 15.0).withTime(15.0, WINDOW);
        Recommendation viaSettings = new RecommendationAlgorithm(new MarketHistoryArtifact()).run(snapshot);
        Recommendation viaExplicit = new RecommendationAlgorithm(new MarketHistoryArtifact(),
                Settings.load().roundLimit()).run(snapshot);

        assertEquals(viaExplicit.ev(), viaSettings.ev(), 1e-9,
                "the production constructor must behave exactly as the live Settings budgets");
        assertEquals(viaExplicit.pAccept(), viaSettings.pAccept(), 1e-9);
    }

    @Test
    void deterministicGivenSameInputs() {
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact(), ROUNDS);
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
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact(), ROUNDS);
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
        // fair-price band ([1400, 2200], centre 1800); marginal cost is 1050 (2 tugs + customs +
        // 5h berth share — S11/L1: cargo is a 2-tug type over its whole template range; this
        // fixture's 700 was the stale 1-tug figure). accept.ev = 1900-1050 = 850. counter(+10%)
        // clamps to 2090 with p_accept~0.19 at MAXIMUM urgency (round 4, fully elapsed,
        // threatened) -> ev ~ -207; counter(+20%) clamps to the band edge 2200 with
        // p_accept~0.12 -> ev ~ -302. Even at max urgency, gambling on a counter is dominated
        // by the -500 withdrawal-penalty risk.
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact(), ROUNDS);
        // "fully elapsed" = a conversation counter that has run all the way down (S14: the time
        // term is the snapshot's own counter; audit P1-9 had rescaled this from the deleted
        // 600-sim-second constant; the resulting urgency is 1.0 either way).
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot("cargo_1", "cargo_vessel", 8.0, 150.0, 30000,
                "general_cargo", "berth_3", 5, 1900.0, 0.0,
                ROUNDS, WINDOW, true).withTime(WINDOW, WINDOW);
        Recommendation rec = algorithm.run(snapshot);
        assertEquals(RecommendationCandidate.ACCEPT, rec.action());
        assertEquals(1900.0, rec.price(), 1e-9);
        assertEquals(850.0, rec.ev(), 1e-6);
    }

    @Test
    void counterBeatsAcceptWhenOfferBarelyCoversMarginalCost() {
        // Mirror case, moved to FERRY at S11: offer 400 barely clears the ferry marginal cost
        // (350 — 0 tugs + customs + 5h berth share), so accept.ev is only 50, while the band
        // centre (1300) is far above the offer, so a counter clamped up to the band floor
        // (1100) wins on the floor's 750 margin over cost. The pre-S11 fixture used
        // cargo_vessel with the stale 1-tug cost (700): at the corrected 1050 the cargo floor
        // (1400) nets only 350 over cost, and the withdrawal-penalty risk makes the counter
        // LOSE to a barely-covering accept — the scenario this test exists for is simply no
        // longer constructible with cargo, and ferry is the type whose cost L1 did not move.
        // counter(+10%) and counter(+20%) both clamp to the same 1100 floor here (both raw
        // values undercut it), so they tie; the algorithm picks the first (+10%).
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact(), ROUNDS);
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot("ferry_2", "ferry", 5.0, 120.0, 12000,
                "general_cargo", "berth_4", 5, 400.0, 0.0, 3, 0.0, false);
        Recommendation rec = algorithm.run(snapshot);
        assertEquals(RecommendationCandidate.COUNTER, rec.action());
        assertEquals(1100.0, rec.price(), 1e-9);
        assertTrue(rec.ev() > 50.0, "counter's EV must beat accept's EV (50.0); got " + rec.ev());
    }

    @Test
    void lowSampleCountUsesIncomeRulesFallback() {
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact(), ROUNDS);
        Recommendation rec = algorithm.run(tankerAtBerth1(6000.0, 1, 60.0));
        assertTrue(rec.marketStats().lowConfidence(), "empty market history must be low-confidence");
        assertEquals(0, rec.marketStats().sampleCount());
    }

    @Test
    void chosenCandidateNeverHasLowerEvThanRejectSilent() {
        // reject_silent (ev=0) is always a candidate; the argmax must never pick something worse.
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact(), ROUNDS);
        Recommendation rec = algorithm.run(tankerAtBerth1(4500.0, 2, 120.0));
        assertTrue(rec.ev() >= 0.0, "chosen action's EV must be >= reject_silent's 0.0; got " + rec.ev());
    }
}
