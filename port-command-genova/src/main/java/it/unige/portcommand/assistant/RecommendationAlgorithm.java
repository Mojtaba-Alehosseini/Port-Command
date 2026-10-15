package it.unige.portcommand.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.MarketStats;
import it.unige.portcommand.core.Settings;
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
     * in spirit of "defaults.json" (task 15); a plain constant until that file exists. S19
     * (PT38a): LATE in the talk only - the last round, or {@link #EARLY_MIN_SECONDS_LEFT} or less on
     * the clock; early a refused counter leaves the ship's offer on the table. */
    public static final double WITHDRAWAL_PENALTY = -500.0;

    private static final double COUNTER_10_PCT = 0.10;
    private static final double COUNTER_20_PCT = 0.20;

    /**
     * S19 (playtest 5, PT38/PT38a): with more than this many seconds left on the tab's clock - and
     * not on the last round - the negotiation is EARLY: a ship that refuses a counter answers with
     * a counter of its own, and its offer stays there to take.
     */
    static final double EARLY_MIN_SECONDS_LEFT = 30.0;

    private final MarketHistoryArtifact marketHistory;
    private final int roundLimit;

    /**
     * Production: the round budget comes from live {@code Settings} (2..8, default 6) — the figure
     * the negotiation tab renders as "Round N of M". The TIME budget is no longer a setting
     * (S14, ADR-18): it is the vessel's own conversation counter, carried by each snapshot.
     */
    public RecommendationAlgorithm(MarketHistoryArtifact marketHistory) {
        this(marketHistory, Settings.load().roundLimit());
    }

    /**
     * Explicit-budget overload, so the round term is testable across its configured range
     * without mutating {@code Settings}' JVM-lifetime cache. Settings stays a static classpath
     * read (INVARIANTS: never threaded through the bootstrap constructor chain) — the one-arg
     * constructor above self-reads it.
     */
    public RecommendationAlgorithm(MarketHistoryArtifact marketHistory, int roundLimit) {
        this.marketHistory = Objects.requireNonNull(marketHistory, "marketHistory");
        this.roundLimit = roundLimit;
    }

    public Recommendation run(WalkInDialogueSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Scoring scoring = score(snapshot);
        ScoredCandidate chosen = argmax(scoring.candidates());
        // compatible/2 (R7) is the only rule PrologQueries.isCompatible actually queries; the fee
        // range / marginal cost are Java-computed figures, never cited as Rn (planning/10 §10.2.7).
        List<String> trace = List.of("R7");
        return new Recommendation(chosen.candidate.action(), chosen.candidate.price(), chosen.pAccept, chosen.ev,
                trace, scoring.stats(), snapshot.vesselType(), snapshot.berthId(), snapshot.durationHours(),
                scoring.compatible() ? "compatible" : "incompatible", scoring.band().fallback());
    }

    /** Test seam (S19, PT38a): every candidate {@link #run} chooses among, in its order. */
    List<RecommendationCandidate> candidates(WalkInDialogueSnapshot snapshot) {
        return score(Objects.requireNonNull(snapshot, "snapshot")).candidates().stream()
                .map(ScoredCandidate::candidate).toList();
    }

    private Scoring score(WalkInDialogueSnapshot snapshot) {
        boolean compatible = PrologQueries.isCompatible(snapshot.berthId(), snapshot.vesselType(),
                snapshot.draft(), snapshot.length(), snapshot.tonnage());
        MarketStats stats = marketHistory.queryStats(snapshot.vesselType(), snapshot.durationHours());
        ScoringBand band = bandFor(snapshot, stats);
        // Both budgets injected (2026-08-10). The round limit was UrgencyCalculator's hardcoded
        // DEFAULT_ROUND_LIMIT while ForwardWalkInToPlayerBehaviour derived its finalRound flag
        // from Settings.roundLimit() — the same figure from two sources, which is exactly the
        // drift the time-limit injection removed one session earlier.
        // S14 (ADR-18): the time term is the vessel's VISIBLE conversation counter — elapsed against
        // total, exactly what the player's tab shows — instead of a nominal Settings window. A
        // snapshot that carries no counter adds no time pressure (and UrgencyCalculator rejects a
        // non-positive limit, so the branch is here rather than inside it).
        boolean hasCounter = snapshot.timeLimitSec() > 0.0;
        double urgency = UrgencyCalculator.compute(snapshot.roundsUsed(), roundLimit,
                hasCounter ? snapshot.timeUsedSec() : 0.0, hasCounter ? snapshot.timeLimitSec() : 1.0,
                snapshot.threatenedWithdrawal());
        double marginalCost = ExpenseRules.marginalCost(snapshot.vesselType(), snapshot.cargoClass(),
                snapshot.durationHours(), snapshot.tonnage());
        // S19 (playtest 5, PT38 / PT38a): "the Assistant only ever says accept". A ship that
        // refuses a counter answers with a counter of its own; it leaves for price only on the LAST
        // round (RealNegotiationEngine rule 2) or when its clock runs out. So early - not the last
        // round, more than EARLY_MIN_SECONDS_LEFT on the tab's clock - a refused counter leaves its
        // offer on the table; late, today's caution stands. The Assistant sees only what the player
        // sees (P-04): the rounds and the clock on the tab, never the ship's budget.
        boolean lastRound = snapshot.threatenedWithdrawal() || snapshot.roundsUsed() >= roundLimit;
        boolean clockShort = hasCounter
                && snapshot.timeLimitSec() - snapshot.timeUsedSec() <= EARLY_MIN_SECONDS_LEFT;
        boolean early = !lastRound && !clockShort;

        // Incompatible berth: no candidate that "involves" it (accept/counter both price a deal
        // at this berth) may be recommended — planning/10 §10.2 step 2. Only reject_silent stays.
        List<ScoredCandidate> candidates = new ArrayList<>();
        if (compatible) {
            candidates.add(scoreAccept(snapshot, marginalCost));
            // PT38a (4): an offer below cost late in the talk is answered "no" - the counter it
            // would take is a long shot, and over budget on the last round the ship leaves. Early
            // it is countered when that pays (the argmax against "no"'s EV of 0 decides).
            boolean belowCost = snapshot.lastVesselOffer() < marginalCost;
            if (early || !belowCost) {
                for (double pct : new double[] {COUNTER_10_PCT, COUNTER_20_PCT}) {
                    ScoredCandidate counter = scoreCounter(snapshot, marginalCost, band, urgency, pct, early);
                    // PT38a (3): the band clamp can pull a counter down to the band top; at or
                    // below the ship's own offer it asks for less than accepting gets.
                    if (counter.candidate().price() > snapshot.lastVesselOffer()) {
                        candidates.add(counter);
                    }
                }
            }
        }
        candidates.add(scoreRejectSilent());
        return new Scoring(compatible, stats, band, candidates);
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
                                                ScoringBand band, double urgency, double pct, boolean early) {
        double raw = snapshot.lastVesselOffer() * (1.0 + pct);
        double price = clamp(raw, band.clampLo(), band.clampHi());
        double pAccept = clamp01(sigmoid((band.sigmoidMean() - price) / band.sigmoidStddev()) * urgency);
        // PT38a (1, 2): what a refusal leaves. Early the ship counters back, and its offer is still
        // there to take - that offer's own margin. Late it leaves: WITHDRAWAL_PENALTY.
        double ifRefused = early ? snapshot.lastVesselOffer() - marginalCost : WITHDRAWAL_PENALTY;
        double ev = pAccept * (price - marginalCost) + (1.0 - pAccept) * ifRefused;
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
            return new ScoringBand(range.lo(), range.hi(), range.midpoint(), sigmoidStddev, range);
        }
        double clampLo = Math.max(0.0, stats.mean() - 2 * stats.stddev());
        double clampHi = stats.mean() + 2 * stats.stddev();
        double sigmoidStddev = Math.max(stats.stddev(), 1.0);
        return new ScoringBand(clampLo, clampHi, stats.mean(), sigmoidStddev, null);
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

    /**
     * @param fallback the static §7.5 band when this band CAME from {@link IncomeRules#berthFeeRange}
     *                 (low-confidence market), else {@code null}. Handed to the {@link Recommendation}
     *                 so the explanation names the band it scored against instead of quoting the
     *                 zeroed market stats — the planning/10 empty-history Hint finding.
     */
    private record ScoringBand(double clampLo, double clampHi, double sigmoidMean, double sigmoidStddev,
                               IncomeRules.PriceRange fallback) {
    }

    private record ScoredCandidate(RecommendationCandidate candidate, double pAccept, double ev) {
    }

    /** One scoring of a snapshot: what {@link #run} reports besides the chosen candidate. */
    private record Scoring(boolean compatible, MarketStats stats, ScoringBand band, List<ScoredCandidate> candidates) {
    }
}
