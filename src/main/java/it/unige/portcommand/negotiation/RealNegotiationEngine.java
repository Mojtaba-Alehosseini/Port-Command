package it.unige.portcommand.negotiation;

import java.util.Map;
import java.util.Random;

/**
 * The real {@link NegotiationEngine}: a pure function of {@code (playerPrice, playerHours, state)}
 * plus one injected {@link Random} draw for the personality roll. Round bookkeeping (incrementing
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
 * negotiated (task 19's play-test). Per CLAUDE.md's authority order PROJECT_DEFINITION
 * beats planning/15's §15.2/§15.3 formulas, so the branch table is coherent buyer mechanics.
 *
 * <p><b>Task 19b — the duration dimension (§7.3: price AND duration).</b> The player's proposed
 * hours are evaluated against the hidden cargo floor {@link WalkInState#minDurationHours()}:
 * <ul>
 *   <li><b>{@code playerHours >= floor}: honored.</b> The spec's acceptance line ("a proposed
 *       duration ≥ the vessel's floor is honored in the closed deal") sets no upper bound — a
 *       berth reservation is a right, not an occupation duty, so extra granted hours cost the
 *       buyer nothing and are never objected to. Every counter/accept echoes the player's
 *       workable hours, so the standing hours follow the player's last stated preference.</li>
 *   <li><b>{@code playerHours < floor}: countered AT the floor, never below, never silently
 *       re-priced into a deal.</b> The hours term of the counter IS the "we need at least Nh"
 *       reply. When the price dimension is already agreed (demand at/below the standing offer,
 *       or the settle roll passes), the counter carries the player's own price back —
 *       price-agreed, hours-blocked — with reason {@link #REASON_TOO_SHORT}.</li>
 * </ul>
 * The deal closes only when BOTH dimensions are agreed. The price branch table is unchanged
 * from task 19:
 * <ol>
 *   <li>{@code playerPrice <= lastOwnOffer} AND hours workable -&gt; ACCEPT (at the demand; a
 *       seller undercutting the standing offer just gets taken up on it).</li>
 *   <li>Rounds exhausted ({@code roundsRemaining <= 0}, the last possible call — counters no
 *       longer exist) -&gt; ACCEPT if the demand is within budget ({@code <= targetPrice}) AND
 *       the hours are workable; WITHDRAW({@value #REASON_TOO_SHORT}) if only the hours block
 *       (the player insisted on a physically impossible stay to the very end); else
 *       WITHDRAW({@value #REASON_OVER_PRICED}).</li>
 *   <li>Affordable ({@code playerPrice <= targetPrice}): a seeded roll against
 *       {@code ACCEPT_THRESHOLD[personality]} decides settle-now vs. hold out, with a forced
 *       settle at {@code roundsRemaining == 1}. Settling ACCEPTs when the hours are workable,
 *       and otherwise counters price-agreed-at-the-demand with the hours pushed to the floor.
 *       Holding out CONCEDES UPWARD from its standing offer toward the demand by
 *       {@link Personality#concessionRate()} — monotone, whole euros, never past the demand.
 *       This contested-affordable branch consumes the roll exactly once regardless of the
 *       hours leg; the price-already-agreed pushback (demand at/below the standing offer,
 *       hours too short) draws NO roll — there is no price decision left to gamble on, and
 *       under workable hours that state accepts without a draw too. Net effect: for every
 *       (price, state) the draw count equals task 19's, so seeded sequences are identical
 *       wherever hours are workable.</li>
 *   <li>Beyond budget ({@code playerPrice > targetPrice}): concede toward the CEILING only
 *       ({@code targetPrice} is a hard walk-away line — no demand above it is ever accepted),
 *       hoping the player comes down before the rounds run out.</li>
 * </ol>
 * Counter reasons name the blocking dimension(s): the price reason alone when the hours are
 * workable; {@code too_short} alone when ONLY the hours block; {@code <priceReason>+too_short}
 * when both dimensions move. {@code WithdrawalBehaviour.outcomeFor} maps the two terminal
 * reasons to {@code WITHDRAW_PRICE} / {@code WITHDRAW_DURATION}.
 *
 * <p>{@code minAcceptablePrice} is no longer read by the engine (it was the inverted
 * design's floor); it stays in {@link WalkInState} as sampled data — the §7.5 band is
 * canonical (INVARIANTS: templates = §7.5 = task-20 IncomeRules) and task 20's income
 * calibration consumes it.
 *
 * <p>{@code roundsRemaining} is derived as {@code roundLimit + 1 - state.round()}, mirroring
 * {@code WalkInVesselAgent}'s own (private) formula. Because {@code WalkInState.round()} is
 * already incremented by {@code recordPlayerCounter} BEFORE {@code evaluate} is ever called,
 * this engine only ever observes {@code roundsRemaining} in {@code {roundLimit-1, ..., 0}}.
 */
public final class RealNegotiationEngine implements NegotiationEngine {

    /** Terminal reason: the player priced the vessel out (maps to {@code WITHDRAW_PRICE}). */
    public static final String REASON_OVER_PRICED = "over_priced";

    /** The hours dimension's reason — as a counter: "we need at least Nh" (the hours term is
     * the floor); as a terminal withdraw at rounds-exhausted: the player never granted the
     * physically required stay (maps to {@code WITHDRAW_DURATION}). */
    public static final String REASON_TOO_SHORT = "too_short";

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
    public Decision evaluate(double playerPrice, int playerHours, WalkInState state) {
        double own = state.lastOwnOffer();
        double ceiling = state.targetPrice();
        int floor = state.minDurationHours();
        int roundsRemaining = Math.max(0, roundLimit + 1 - state.round());
        boolean hoursOk = playerHours >= floor;
        // The hours term every counter carries: echo workable hours; push a too-short
        // proposal back up to the floor (never below) — the "we need at least Nh" reply.
        int hoursTerm = hoursOk ? playerHours : floor;

        // 1. The demand is within what we already offered, for a workable stay: take it.
        if (playerPrice <= own && hoursOk) {
            return Decision.accept(playerHours, "at_or_below_our_offer");
        }
        // 2. Last call — counters no longer exist: settle if BOTH terms work, else leave,
        //    naming the dimension that killed it (an affordable price with an impossible
        //    stay is a duration failure, not a price one).
        if (roundsRemaining <= 0) {
            if (playerPrice <= ceiling) {
                return hoursOk
                        ? Decision.accept(playerHours, "rounds_exhausted_affordable")
                        : Decision.withdraw(REASON_TOO_SHORT);
            }
            return Decision.withdraw(REASON_OVER_PRICED);
        }
        // 3. Price already agreed (demand at/below the standing offer) but the stay is too
        //    short: hand the player's own price back with the hours at the floor. No roll —
        //    there is no price decision left to gamble on.
        if (playerPrice <= own) {
            return Decision.counter(Math.floor(playerPrice), hoursTerm, REASON_TOO_SHORT);
        }
        // 4. Affordable: roll to settle now (forced on the last open round), else concede
        //    upward. The roll is consumed on every affordable evaluation — hours branch
        //    included — so seeded sequences don't fork on the duration dimension.
        if (playerPrice <= ceiling) {
            double drawn = roll.nextDouble();
            if (drawn < ACCEPT_THRESHOLD.get(state.personality()) || roundsRemaining == 1) {
                return hoursOk
                        ? Decision.accept(playerHours, "in_band_roll_or_last_round")
                        : Decision.counter(Math.floor(playerPrice), hoursTerm, REASON_TOO_SHORT);
            }
            double conceded = concede(own, playerPrice, state.personality());
            // Checkpoint-#5 minor 2 (WALKIN-14): a concession step that REACHES the demand is
            // agreement, not a counter — "I'll pay 5583" in reply to "give me 5583" read as a
            // haggle over nothing. Only when the hours also work (otherwise the counter below
            // still carries the meaningful floor pushback). The roll above stays consumed
            // either way, so task-19's seeded round sequences are unchanged.
            if (hoursOk && conceded >= Math.floor(playerPrice)) {
                return Decision.accept(playerHours, "concession_reached_demand");
            }
            return Decision.counter(conceded, hoursTerm,
                    hoursOk ? "conceding_toward_demand" : "conceding_toward_demand+" + REASON_TOO_SHORT);
        }
        // 5. Beyond our budget: creep toward the ceiling and hope the player comes down.
        return Decision.counter(concede(own, ceiling, state.personality()), hoursTerm,
                hoursOk ? "conceding_toward_ceiling" : "conceding_toward_ceiling+" + REASON_TOO_SHORT);
    }

    /**
     * Buyer concession: move UP from the vessel's standing offer toward {@code toward} by the
     * personality's concession rate — at least €1 (guaranteed visible progress), whole euros
     * (task 19 STEP 1: the player never sees cents), never past {@code toward} (a buyer
     * approaches the demand/ceiling from below; it does not overshoot).
     *
     * <p>The cap is {@code floor(toward)}, not {@code toward}: when the step would overshoot, a
     * bare {@code min(toward, …)} would return {@code toward} verbatim, which is fractional both
     * when the player types cents (branch 4, {@code toward = playerPrice}) and always in branch 5
     * ({@code toward = targetPrice}, an unrounded uniform sample — flooring it also keeps the hidden
     * ceiling off the wire). Flooring keeps the result whole and still {@code <= toward} (adversarial
     * review M1, 2026-07-17). Branch 3's price-agreed counter floors for the same no-cents reason.
     */
    private static double concede(double own, double toward, Personality personality) {
        double step = Math.max(1.0, Math.round(personality.concessionRate() * (toward - own)));
        return Math.min(Math.floor(toward), own + step);
    }
}
