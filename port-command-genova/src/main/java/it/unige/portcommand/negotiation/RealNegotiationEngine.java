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
 * <p><b>Task 19 rewrite — the vessel is the BUYER.</b> The canonical economy
 * (PROJECT_DEFINITION §7.5: berth fees are port INCOME; {@code Frame}'s commerce_sell
 * contract: "the player SELLS berth/escort services and RECEIVES the money") makes the
 * walk-in the buyer of berth time and the player the seller quoting a fee. The original
 * task-15 implementation had the comparisons seller-side-inverted — it ACCEPTED any
 * demand {@code >= targetPrice} (so demanding an absurd fee closed instantly at that fee)
 * and countered UP past its own opening ask (ask €5744, player bids €6000, vessel "counters"
 * €6120) — indefensible for a payer, but unobservable until the first human actually
 * negotiated (task 19's play-test). Per the design rules' authority order PROJECT_DEFINITION
 * beats planning/15's §15.2/§15.3 formulas, so the branch table is now coherent
 * buyer mechanics:
 * <ol>
 *   <li>{@code playerPrice <= lastOwnOffer} — the demand is within what the vessel already
 *       has on the table -&gt; ACCEPT (at the demand; a seller undercutting the standing
 *       offer just gets taken up on it).</li>
 *   <li>Rounds exhausted ({@code roundsRemaining <= 0}, the last possible call) -&gt; ACCEPT
 *       if the demand is still within budget ({@code <= targetPrice}), else
 *       WITHDRAW("over_priced") — the reason string finally means what it says: the player
 *       priced the vessel out.</li>
 *   <li>Affordable ({@code playerPrice <= targetPrice}): a seeded roll against
 *       {@code ACCEPT_THRESHOLD[personality]} decides settle-now vs. hold out, with a forced
 *       ACCEPT at {@code roundsRemaining == 1} (the last round it can still afford to close
 *       on its own terms). Holding out CONCEDES UPWARD from its standing offer toward the
 *       demand by {@link Personality#concessionRate()} — monotone, whole euros, never past
 *       the demand.</li>
 *   <li>Beyond budget ({@code playerPrice > targetPrice}): concede toward the CEILING only
 *       ({@code targetPrice} is a hard walk-away line — no demand above it is ever accepted),
 *       hoping the player comes down before the rounds run out.</li>
 * </ol>
 *
 * <p>{@code minAcceptablePrice} is no longer read by the engine (it was the inverted
 * design's floor); it stays in {@link WalkInState} as sampled data — the §7.5 band is
 * canonical (INVARIANTS: templates = §7.5 = task-20 IncomeRules) and task 20's income
 * calibration consumes it. {@code Personality.concessionRate}, declared in task 07 "for
 * task 15" but never consumed by the inverted engine, is finally load-bearing.
 *
 * <p>{@code roundsRemaining} is derived as {@code roundLimit + 1 - state.round()}, mirroring
 * {@code WalkInVesselAgent}'s own (private) formula. Because {@code WalkInState.round()} is
 * already incremented by {@code recordPlayerCounter} BEFORE {@code evaluate} is ever called,
 * this engine only ever observes {@code roundsRemaining} in {@code {roundLimit-1, ..., 0}}.
 */
public final class RealNegotiationEngine implements NegotiationEngine {

    /** The only reason string an engine-originated WITHDRAW ever carries (see class javadoc). */
    public static final String REASON_OVER_PRICED = "over_priced";

    private static final Map<Personality, Double> ACCEPT_THRESHOLD = Map.of(
            Personality.AGGRESSIVE, 0.30,
            Personality.NEUTRAL, 0.55,
            Personality.DESPERATE, 0.80);

    private final int roundLimit;
    private final Random roll;

    public RealNegotiationEngine(int roundLimit, Random roll) {
        this.roundLimit = roundLimit;
        this.roll = roll;
    }

    @Override
    public Decision evaluate(double playerPrice, WalkInState state) {
        double own = state.lastOwnOffer();
        double ceiling = state.targetPrice();
        int roundsRemaining = Math.max(0, roundLimit + 1 - state.round());

        // 1. The demand is within what we already offered: take it.
        if (playerPrice <= own) {
            return Decision.accept("at_or_below_our_offer");
        }
        // 2. Last call: affordable -> settle; priced out -> leave.
        if (roundsRemaining <= 0) {
            return playerPrice <= ceiling
                    ? Decision.accept("rounds_exhausted_affordable")
                    : Decision.withdraw(REASON_OVER_PRICED);
        }
        // 3. Affordable: roll to settle now (forced on the last open round), else concede upward.
        if (playerPrice <= ceiling) {
            double drawn = roll.nextDouble();
            if (drawn < ACCEPT_THRESHOLD.get(state.personality()) || roundsRemaining == 1) {
                return Decision.accept("in_band_roll_or_last_round");
            }
            return Decision.counter(concede(own, playerPrice, state.personality()), "conceding_toward_demand");
        }
        // 4. Beyond our budget: creep toward the ceiling and hope the player comes down.
        return Decision.counter(concede(own, ceiling, state.personality()), "conceding_toward_ceiling");
    }

    /**
     * Buyer concession: move UP from the vessel's standing offer toward {@code toward} by the
     * personality's concession rate — at least €1 (guaranteed visible progress), whole euros
     * (task 19 STEP 1: the player never sees cents), never past {@code toward} (a buyer
     * approaches the demand/ceiling from below; it does not overshoot).
     *
     * <p>The cap is {@code floor(toward)}, not {@code toward}: when the step would overshoot, a
     * bare {@code min(toward, …)} would return {@code toward} verbatim, which is fractional both
     * when the player types cents (branch 3, {@code toward = playerPrice}) and always in branch 4
     * ({@code toward = targetPrice}, an unrounded uniform sample — flooring it also keeps the hidden
     * ceiling off the wire). Flooring keeps the result whole and still {@code <= toward} (adversarial
     * review M1, 2026-07-17).
     */
    private static double concede(double own, double toward, Personality personality) {
        double step = Math.max(1.0, Math.round(personality.concessionRate() * (toward - own)));
        return Math.min(Math.floor(toward), own + step);
    }
}
