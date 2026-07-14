package it.unige.portcommand.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.MarketStats;
import it.unige.portcommand.harbourmaster.financial.ExpenseRules;
import it.unige.portcommand.harbourmaster.financial.IncomeRules;
import it.unige.portcommand.prolog.PrologQueries;

/**
 * Pure EV-based recommendation heuristic (planning/10 §10.2, v1.1 fixes). {@link #run} is a
 * deterministic function of its {@link WalkInDialogueSnapshot} input plus two read-only
 * collaborators ({@link MarketHistoryArtifact}, the static {@link PrologQueries} facade) — no
 * JADE dependency, no hidden hidden-belief reads (the snapshot type structurally cannot carry
 * one; see {@link WalkInDialogueSnapshot}).
 */
public final class RecommendationAlgorithm {

    /** EV penalty applied to a rejected counter-offer (the vessel walks away). Configurable
     * in spirit of "defaults.json" (task 15); a plain constant until that file exists. */
    public static final double WITHDRAWAL_PENALTY = -500.0;

    private static final double COUNTER_10_PCT = 0.10;
    private static final double COUNTER_20_PCT = 0.20;

    private final MarketHistoryArtifact marketHistory;

    public RecommendationAlgorithm(MarketHistoryArtifact marketHistory) {
        this.marketHistory = Objects.requireNonNull(marketHistory, "marketHistory");
    }

    public Recommendation run(WalkInDialogueSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        boolean compatible = PrologQueries.isCompatible(snapshot.berthId(), snapshot.vesselType(),
                snapshot.draft(), snapshot.length(), snapshot.tonnage());
        MarketStats stats = marketHistory.queryStats(snapshot.vesselType(), snapshot.durationHours());
        ScoringBand band = bandFor(snapshot, stats);
        double urgency = UrgencyCalculator.compute(snapshot.roundsUsed(), UrgencyCalculator.DEFAULT_ROUND_LIMIT,
                snapshot.timeUsedSec(), UrgencyCalculator.DEFAULT_TIME_LIMIT_SEC, snapshot.threatenedWithdrawal());
        double marginalCost = ExpenseRules.marginalCost(snapshot.vesselType(), snapshot.cargoClass(),
                snapshot.durationHours(), snapshot.tonnage());

        // Incompatible berth: no candidate that "involves" it (accept/counter both price a deal
        // at this berth) may be recommended — planning/10 §10.2 step 2. Only reject_silent stays.
        List<ScoredCandidate> candidates = new ArrayList<>();
        if (compatible) {
            candidates.add(scoreAccept(snapshot, marginalCost));
            candidates.add(scoreCounter(snapshot, marginalCost, band, urgency, COUNTER_10_PCT));
            candidates.add(scoreCounter(snapshot, marginalCost, band, urgency, COUNTER_20_PCT));
        }
        candidates.add(scoreRejectSilent());

        ScoredCandidate chosen = argmax(candidates);
        // compatible/2 (R7) is the only rule PrologQueries.isCompatible actually queries; the fee
        // range / marginal cost are Java-computed figures, never cited as Rn (planning/10 §10.2.7).
        List<String> trace = List.of("R7");
        return new Recommendation(chosen.candidate.action(), chosen.candidate.price(), chosen.pAccept, chosen.ev,
                trace, stats, snapshot.vesselType(), snapshot.berthId(), snapshot.durationHours(),
                compatible ? "compatible" : "incompatible");
    }

    private static ScoredCandidate scoreAccept(WalkInDialogueSnapshot snapshot, double marginalCost) {
        double price = snapshot.lastVesselOffer();
        // Deterministic — no p_accept (v1.1 fix). Accepting the vessel's own standing offer
        // closes the deal outright; p_accept models the VESSEL accepting the PLAYER's counter,
        // so applying it here would double-discount and systematically over-recommend countering.
        double ev = price - marginalCost;
        return new ScoredCandidate(new RecommendationCandidate(RecommendationCandidate.ACCEPT, price, marginalCost),
                1.0, ev);
    }

    private static ScoredCandidate scoreCounter(WalkInDialogueSnapshot snapshot, double marginalCost,
                                                ScoringBand band, double urgency, double pct) {
        double raw = snapshot.lastVesselOffer() * (1.0 + pct);
        double price = clamp(raw, band.clampLo(), band.clampHi());
        double pAccept = clamp01(sigmoid((band.sigmoidMean() - price) / band.sigmoidStddev()) * urgency);
        double ev = pAccept * (price - marginalCost) + (1.0 - pAccept) * WITHDRAWAL_PENALTY;
        return new ScoredCandidate(new RecommendationCandidate(RecommendationCandidate.COUNTER, price, marginalCost),
                pAccept, ev);
    }

    private static ScoredCandidate scoreRejectSilent() {
        // The "do nothing" baseline (planning/10 §10.2 step 5).
        return new ScoredCandidate(
                new RecommendationCandidate(RecommendationCandidate.REJECT_SILENT, 0.0, 0.0), 0.0, 0.0);
    }

    /**
     * Clamp bounds + sigmoid reference point/scale, from either the real market query or (when
     * {@code sampleCount < 10}) the static {@link IncomeRules#berthFeeRange} fallback band
     * (planning/10 §10.2: "price = midpoint of the range, p_accept estimated from distance to
     * the band centre"). The fallback's own [lo, hi] edges become the clamp bounds directly (no
     * synthesised ±2*stddev round trip); a quarter of the band width stands in for "stddev" in
     * the sigmoid, mirroring the real path's {@code mean ± 2*stddev == [lo, hi]} relationship.
     */
    private static ScoringBand bandFor(WalkInDialogueSnapshot snapshot, MarketStats stats) {
        if (stats.lowConfidence()) {
            IncomeRules.PriceRange range = IncomeRules.berthFeeRange(snapshot.berthId(), snapshot.vesselType());
            double sigmoidStddev = Math.max((range.hi() - range.lo()) / 4.0, 1.0);
            return new ScoringBand(range.lo(), range.hi(), range.midpoint(), sigmoidStddev);
        }
        double clampLo = Math.max(0.0, stats.mean() - 2 * stats.stddev());
        double clampHi = stats.mean() + 2 * stats.stddev();
        double sigmoidStddev = Math.max(stats.stddev(), 1.0);
        return new ScoringBand(clampLo, clampHi, stats.mean(), sigmoidStddev);
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    private static ScoredCandidate argmax(List<ScoredCandidate> candidates) {
        ScoredCandidate best = candidates.get(0);
        for (ScoredCandidate c : candidates) {
            if (c.ev > best.ev) {
                best = c;
            }
        }
        return best;
    }

    private record ScoringBand(double clampLo, double clampHi, double sigmoidMean, double sigmoidStddev) {
    }

    private record ScoredCandidate(RecommendationCandidate candidate, double pAccept, double ev) {
    }
}
