package it.unige.portcommand.negotiation;

import java.util.Map;
import java.util.Random;

/**
 * The real {@link NegotiationEngine}: a pure function of {@code (playerPrice, state)} plus one
 * injected {@link Random} draw for the personality roll. Round bookkeeping (incrementing
 * {@link WalkInState#round()}) stays in {@code behaviours.negotiation} (task 07) — this class
 * never mutates {@code state}; it only reads {@link WalkInState#round()} to derive
 * {@code roundsRemaining} for itself.
 *
 * <p>Branch order (task-15 session brief, reconciled against
 * {@code planning/15_negotiation_engine.md} §15.2/§15.3 where the brief was silent — see the
 * in-band counter formula note below):
 * <ol>
 *   <li>{@code playerPrice >= targetPrice} -&gt; ACCEPT, always.
 *   <li>Rounds exhausted ({@code roundsRemaining <= 0}, the last possible call) -&gt; ACCEPT if the
 *       price is still in-band (graceful fallback), else WITHDRAW("over_priced").
 *   <li>{@code playerPrice < minAcceptablePrice} -&gt; COUNTER at
 *       {@code (min+target)/2 * REJECT_COUNTER_MOD[personality]}.
 *   <li>In-band: a seeded roll against {@code ACCEPT_THRESHOLD[personality]} decides ACCEPT vs.
 *       COUNTER, with a forced ACCEPT at {@code roundsRemaining == 1} (the last round the vessel
 *       can still afford to hold out). The in-band COUNTER formula is
 *       {@code playerPrice * (1 + COUNTER_UP_PCT[personality])} — from §15.2's
 *       {@code COUNTER_UP_PCT}, NOT the below-minimum formula in branch 3 (an earlier draft reused
 *       branch 3's formula here, which makes the vessel undercut its own last offer near the
 *       target price; caught in design review).
 * </ol>
 * Every counter is clamped to {@code [minAcceptablePrice, targetPrice * 1.3]} — the upper bound
 * keeps a pathological personality modifier from producing an absurd counter.
 *
 * <p>{@code roundsRemaining} is derived as {@code roundLimit + 1 - state.round()}, mirroring
 * {@code WalkInVesselAgent}'s own (private) formula. Because {@code WalkInState.round()} is
 * already incremented by {@code recordPlayerCounter} BEFORE {@code evaluate} is ever called, this
 * engine only ever observes {@code roundsRemaining} in {@code {roundLimit-1, ..., 0}} — one lower
 * than {@code planning/15}'s own §15.3 numbering, which assumed the engine itself decrements
 * rounds (it doesn't; that stays task-07's job). Both numbering schemes describe the same dialogue
 * moments under this offset; do not "correct" this back to a literal copy of the planning doc's
 * thresholds.
 */
public final class RealNegotiationEngine implements NegotiationEngine {

    /** The only reason string an engine-originated WITHDRAW ever carries (see class javadoc). */
    public static final String REASON_OVER_PRICED = "over_priced";

    private static final double COUNTER_CLAMP_UPPER_MULTIPLIER = 1.3;

    private static final Map<Personality, Double> ACCEPT_THRESHOLD = Map.of(
            Personality.AGGRESSIVE, 0.30,
            Personality.NEUTRAL, 0.55,
            Personality.DESPERATE, 0.80);

    private static final Map<Personality, Double> REJECT_COUNTER_MOD = Map.of(
            Personality.AGGRESSIVE, 1.10,
            Personality.NEUTRAL, 1.00,
            Personality.DESPERATE, 0.95);

    private static final Map<Personality, Double> COUNTER_UP_PCT = Map.of(
            Personality.AGGRESSIVE, 0.08,
            Personality.NEUTRAL, 0.04,
            Personality.DESPERATE, 0.02);

    private final int roundLimit;
    private final Random roll;

    public RealNegotiationEngine(int roundLimit, Random roll) {
        this.roundLimit = roundLimit;
        this.roll = roll;
    }

    @Override
    public Decision evaluate(double playerPrice, WalkInState state) {
        double min = state.minAcceptablePrice();
        double target = state.targetPrice();
        int roundsRemaining = Math.max(0, roundLimit + 1 - state.round());

        if (playerPrice >= target) {
            return Decision.accept("at_or_above_target");
        }
        if (roundsRemaining <= 0) {
            return playerPrice >= min
                    ? Decision.accept("rounds_exhausted_in_band")
                    : Decision.withdraw(REASON_OVER_PRICED);
        }
        if (playerPrice < min) {
            double ideal = (min + target) / 2.0 * REJECT_COUNTER_MOD.get(state.personality());
            return Decision.counter(clamp(ideal, min, target), "below_minimum");
        }

        // In-band: min <= playerPrice < target, roundsRemaining >= 1.
        double drawn = roll.nextDouble();
        double threshold = ACCEPT_THRESHOLD.get(state.personality());
        if (drawn < threshold || roundsRemaining == 1) {
            return Decision.accept("in_band_roll_or_last_round");
        }
        double ideal = playerPrice * (1 + COUNTER_UP_PCT.get(state.personality()));
        return Decision.counter(clamp(ideal, min, target), "in_band_holding_out");
    }

    /** Clamped to {@code [min, target*1.3]} and rounded to whole euros at the source
     * (task 19 STEP 1 — same bug class/fix as the opening offer; a counter must never
     * carry cents either). */
    private static double clamp(double ideal, double min, double target) {
        double upper = target * COUNTER_CLAMP_UPPER_MULTIPLIER;
        return Math.round(Math.min(Math.max(ideal, min), upper));
    }
}
