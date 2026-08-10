package it.unige.portcommand.assistant;

/**
 * Pure urgency heuristic for {@link RecommendationAlgorithm} (planning/10 §10.1, v1.1 fix).
 * Blends round-progress, time-progress, and an observed withdrawal threat into a single
 * [0.4, 1.0] factor that scales each counter candidate's acceptance probability.
 *
 * <p><b>Units (settled 2026-08-10, audit P1-9):</b> {@code timeUsedSec} and {@code timeLimitSec}
 * are both REAL seconds — the same pacing-invariant scale the negotiation tab's "~Ns remaining"
 * countdown uses. See the note where the old sim-second constant used to live.
 */
public final class UrgencyCalculator {

    // DEFAULT_ROUND_LIMIT (4) was REMOVED 2026-08-10, for the same reason and by the same
    // reasoning as DEFAULT_TIME_LIMIT_SEC one session earlier: production stopped reading it.
    //
    // It described PROJECT_DEFINITION §7.3's round cap as a fixed constant, but the cap is a
    // configurable Settings value (2..8) that RealNegotiationEngine, the offer strip's
    // "Round N of M" and the HarbourMaster's final-round flag all already took from Settings.
    // Only this calculator's caller still passed the constant, so the Assistant could reason
    // from a budget of 4 while the player was shown a budget of 8. RecommendationAlgorithm now
    // injects Settings.roundLimit(); both of compute()'s budgets are caller-supplied, and there
    // is no "default" left that production honours.

    // DEFAULT_TIME_LIMIT_SEC (600.0, "10 sim-minutes") was REMOVED 2026-08-10 (audit P1-9,
    // resolving the scale conflict INVARIANTS and ADR-14 D13 both flagged as open).
    //
    // It could not be made to work. Its own javadoc called it sim-seconds, but at the default
    // 300 real-s/day mapping 600 sim-seconds is ~2.1 REAL seconds, so once timeUsedSec carried a
    // real elapsed figure the term saturated almost immediately — swapping a permanently-dead
    // term (it was fed a hard-coded 0.0) for a permanently-maxed one. Meanwhile the vessel's
    // actual patience is 140-480 sim-MINUTES and the countdown the player watches is
    // Settings.timeLimitSeconds REAL seconds: three different scales for one quantity.
    //
    // Resolved in favour of the player-visible one: the caller passes the LIVE
    // Settings.timeLimitSeconds (30..180, default 60) and timeUsedSec is elapsed REAL seconds off
    // the shared SimClock conversion, so the urgency term and the on-screen countdown measure the
    // same thing. That window is PUBLIC — it is deliberately not the vessel's hidden
    // maxWaitMinutes (P-04), so the Assistant reasons from what the player can see, not from
    // privileged state. No fallback constant survives: Settings.load() falls back to
    // Settings.defaults() internally, so a "Settings unavailable" case does not exist.

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
