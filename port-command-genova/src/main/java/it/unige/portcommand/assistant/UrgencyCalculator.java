package it.unige.portcommand.assistant;

/**
 * Pure urgency heuristic for {@link RecommendationAlgorithm} (planning/10 §10.1, v1.1 fix).
 * Blends round-progress, time-progress, and an observed withdrawal threat into a single
 * [0.4, 1.0] factor that scales each counter candidate's acceptance probability.
 */
public final class UrgencyCalculator {

    /**
     * PROJECT_DEFINITION §7.3 — walk-in negotiations run up to 4 rounds. This is a fixed game
     * rule applied identically to every negotiation, never a per-vessel hidden belief, so using
     * it does not violate the observable-inputs rule (unlike {@code WalkInState.maxWaitMinutes},
     * which IS hidden and must never be read here).
     */
    public static final int DEFAULT_ROUND_LIMIT = 4;

    /**
     * Nominal negotiation time budget (10 sim-minutes), used ONLY to normalise the urgency
     * heuristic's time-progress term. This is a fixed, vessel-type-agnostic design constant —
     * NOT any specific vessel's hidden {@code maxWaitMinutes} patience (that field is a hidden
     * belief per INVARIANTS.md and must never be read here, v1.1 P-04). Chosen as roughly the
     * midpoint of the observed {@code max_wait_minutes_range} spread across
     * {@code vessel_templates.json} (5-40 minutes) and the ~4-round cadence (TimeoutWithdrawal
     * re-checks every 5 sim-seconds; 4 rounds at a couple of minutes each lands near 10 minutes).
     */
    public static final double DEFAULT_TIME_LIMIT_SEC = 600.0;

    private UrgencyCalculator() {
    }

    /**
     * {@code 0.4 + 0.6 * clamp01(0.4*roundsUsed/roundLimit + 0.4*timeUsedSec/timeLimitSec +
     * 0.2*(threatened?1:0))} — range <b>[0.4, 1.0]</b>.
     *
     * <p>The 0.4 floor is load-bearing: without it, the raw {@code clamp01(...)} term is 0.0 on
     * the opening offer (roundsUsed=0, timeUsedSec≈0), which zeroes {@code p_accept} for every
     * counter candidate, drives every priced candidate's EV to the withdrawal penalty (-500),
     * and makes {@code reject_silent} (EV=0) win by default — i.e. the Assistant would
     * recommend "reject silently" on every first Hint click.
     */
    public static double compute(int roundsUsed, int roundLimit, double timeUsedSec, double timeLimitSec,
                                 boolean threatened) {
        if (roundLimit <= 0) {
            throw new IllegalArgumentException("roundLimit must be > 0, got " + roundLimit);
        }
        if (timeLimitSec <= 0) {
            throw new IllegalArgumentException("timeLimitSec must be > 0, got " + timeLimitSec);
        }
        double roundProgress = (double) roundsUsed / roundLimit;
        double timeProgress = timeUsedSec / timeLimitSec;
        double raw = 0.4 * roundProgress + 0.4 * timeProgress + 0.2 * (threatened ? 1.0 : 0.0);
        return 0.4 + 0.6 * clamp01(raw);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
