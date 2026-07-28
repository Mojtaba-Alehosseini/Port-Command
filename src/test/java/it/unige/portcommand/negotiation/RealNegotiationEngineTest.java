package it.unige.portcommand.negotiation;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven coverage of {@link RealNegotiationEngine}'s branch table: buyer semantics on the
 * price dimension (task 19 rewrite — see the engine's class javadoc for why the original task-15
 * table was inverted and which documents mandate this direction) × the duration dimension
 * (task 19b — §7.3 negotiates price AND duration; hours at or above the hidden cargo floor are
 * honored, below it the counter pushes back AT the floor). Uses {@link WalkInState}'s canonical
 * constructor directly to pin an exact {@code round} / {@code lastOwnOffer} / standing hours per
 * case, and a fixed-return {@link Random} subclass to control the personality roll
 * deterministically.
 */
class RealNegotiationEngineTest {

    private static final int ROUND_LIMIT = 4;
    private static final double CEILING = 7000.0; // targetPrice = the vessel's hard budget
    private static final double MIN = 4500.0;     // sampled but engine-unused (task-20 data)
    private static final int PREFERRED = 8;       // dealHours — the announced preferred stay
    private static final int FLOOR = 5;           // minDurationHours — the hidden cargo floor

    /** {@code roundsRemaining = ROUND_LIMIT + 1 - round}, so round=2..5 gives rr=3,2,1,0. */
    private static WalkInState stateAt(int round, double ownOffer, Personality personality) {
        return new WalkInState("cargo_vessel", MIN, CEILING, personality, 120, FLOOR,
                round, 0L, 0.0, ownOffer, 0, PREFERRED, PREFERRED);
    }

    private static RealNegotiationEngine engineWithFixedRoll(double fixedRoll) {
        return new RealNegotiationEngine(ROUND_LIMIT, new Random() {
            @Override
            public double nextDouble() {
                return fixedRoll;
            }
        });
    }

    // ==================== branch 1: demand at or below the standing offer ====================

    @Test
    void demandAtOrBelowTheVesselsOwnOffer_withWorkableHours_acceptsImmediately() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // roll must not be consulted
        WalkInState state = stateAt(2, 5744.0, Personality.AGGRESSIVE); // rr=3

        Decision atOffer = engine.evaluate(5744.0, PREFERRED, state);
        assertEquals(Decision.Type.ACCEPT, atOffer.type(),
                "a demand equal to the standing offer is already agreed");
        assertEquals(PREFERRED, atOffer.newHours(), "the agreed stay is the player's proposal");

        Decision below = engine.evaluate(5000.0, 6, state); // 6 >= FLOOR: workable
        assertEquals(Decision.Type.ACCEPT, below.type(),
                "a demand BELOW the standing offer is a bargain for the buyer — instant deal");
        assertEquals(6, below.newHours(), "a workable compressed stay is honored as proposed");
    }

    @Test
    void hoursAboveThePreferredStayAreStillHonored() {
        // The 19b spec's acceptance line sets no upper bound: "a proposed duration >= the
        // vessel's floor is honored in the closed deal". A berth reservation is a right, not an
        // occupation duty — extra granted hours cost the buyer nothing.
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        WalkInState state = stateAt(2, 5744.0, Personality.AGGRESSIVE);

        Decision decision = engine.evaluate(5000.0, 23, state); // 23 > PREFERRED(8)

        assertEquals(Decision.Type.ACCEPT, decision.type());
        assertEquals(23, decision.newHours(), "hours above preferred are honored, not clamped");
    }

    @Test
    void agreedPriceButTooShortStay_countersThePlayersOwnPriceAtTheFloor() {
        // The price dimension is settled (demand below the standing offer) but the stay is
        // below the cargo floor: the counter hands the player's own price back with the hours
        // pushed UP to the floor — the "we need at least Nh" reply. Never an accept, never a
        // silent re-price.
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // roll must not be consulted
        WalkInState state = stateAt(2, 5744.0, Personality.AGGRESSIVE); // rr=3

        Decision decision = engine.evaluate(5000.50, 3, state); // 3 < FLOOR(5); player typed cents

        assertEquals(Decision.Type.COUNTER, decision.type());
        assertEquals(5000.0, decision.newCounter(), 0.001,
                "price-agreed pushback carries the player's own price, floored to whole euros");
        // DELIBERATE monotonicity exception (adversarial review 19b): this is the ONE branch
        // where the vessel's wire price moves BELOW its standing offer (5744 -> 5000) — it is
        // re-anchoring to the player's own lower demand while blocking on hours, never conceding
        // in the wrong direction. Every other counter rises (repeatedIdenticalDemandsConverge…).
        assertTrue(decision.newCounter() < state.lastOwnOffer(),
                "re-anchors down to the demand the player themselves made");
        assertEquals(FLOOR, decision.newHours(), "the hours term IS the floor — 'we need at least 5h'");
        assertEquals("too_short", decision.reason());
    }

    // ==================== branch 2: rounds exhausted ====================

    @Test
    void roundsExhausted_affordableDemandWithWorkableHours_settles() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        WalkInState state = stateAt(5, 5744.0, Personality.NEUTRAL); // rr=0

        Decision decision = engine.evaluate(CEILING, PREFERRED, state);

        assertEquals(Decision.Type.ACCEPT, decision.type(),
                "at the last call an affordable (<= ceiling) demand with a workable stay is taken");
        assertEquals(PREFERRED, decision.newHours());
    }

    @Test
    void roundsExhausted_affordableButStillTooShort_withdrawsAsDurationFailure() {
        // The one terminal case the duration dimension owns: the fee was fine at the final
        // call, but the player still insisted on a physically impossible stay. Counters no
        // longer exist, accepting is physically impossible -> leave, naming the TRUE reason
        // (WithdrawalBehaviour maps it to WITHDRAW_DURATION, not WITHDRAW_PRICE).
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // roll must not rescue it
        WalkInState state = stateAt(5, 5744.0, Personality.DESPERATE); // rr=0

        Decision decision = engine.evaluate(6000.0, FLOOR - 1, state);

        assertEquals(Decision.Type.WITHDRAW, decision.type());
        assertEquals("too_short", decision.reason());
    }

    @Test
    void roundsExhausted_demandBeyondBudget_withdrawsOverPriced() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // would accept if roll were consulted
        WalkInState state = stateAt(5, 5744.0, Personality.DESPERATE); // rr=0

        Decision decision = engine.evaluate(CEILING + 1, PREFERRED, state);

        assertEquals(Decision.Type.WITHDRAW, decision.type());
        // Pinned exactly: WithdrawalBehaviour.outcomeFor switches on this literal string to
        // record the canonical Deal.Outcome.WITHDRAW_PRICE — and under buyer semantics the
        // name finally means what it says: the player priced the vessel out.
        assertEquals("over_priced", decision.reason());
    }

    // ==================== branch 4: affordable, rounds remain ====================

    @Test
    void affordable_rollBelowThreshold_acceptsTheDemandAndItsHours() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // below every personality threshold
        WalkInState state = stateAt(2, 5744.0, Personality.AGGRESSIVE); // rr=3, threshold 0.30

        Decision decision = engine.evaluate(6500.0, 6, state);

        assertEquals(Decision.Type.ACCEPT, decision.type());
        assertEquals(6, decision.newHours());
    }

    @Test
    void affordable_rollWouldSettleButHoursTooShort_countersPriceAgreedAtTheFloor() {
        // The settle roll passes, so the PRICE decision is "take the demand" — but the stay is
        // below the floor, so the deal cannot close: counter with the demand echoed (whole
        // euros) and the hours at the floor.
        RealNegotiationEngine engine = engineWithFixedRoll(0.01);
        WalkInState state = stateAt(2, 5744.0, Personality.AGGRESSIVE); // rr=3

        Decision decision = engine.evaluate(6500.0, 2, state); // 2 < FLOOR(5)

        assertEquals(Decision.Type.COUNTER, decision.type());
        assertEquals(6500.0, decision.newCounter(), 0.001, "price-settled: the demand comes back");
        assertEquals(FLOOR, decision.newHours());
        assertEquals("too_short", decision.reason());
    }

    @Test
    void affordable_rollAboveThreshold_concedesUpward_neverPastTheDemand_echoingWorkableHours() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // above every personality threshold
        WalkInState state = stateAt(2, 5744.0, Personality.DESPERATE); // rr=3, concession 0.40

        Decision decision = engine.evaluate(6500.0, 6, state);

        assertEquals(Decision.Type.COUNTER, decision.type());
        // 5744 + round(0.40 * (6500-5744)) = 5744 + 302 = 6046: strictly above its own last
        // offer (visible progress), strictly below the demand (a buyer approaches from below).
        assertEquals(6046.0, decision.newCounter(), 0.001);
        assertTrue(decision.newCounter() > 5744.0 && decision.newCounter() < 6500.0);
        assertEquals(6, decision.newHours(), "workable player hours are echoed on the counter");
        assertEquals("conceding_toward_demand", decision.reason());
    }

    @Test
    void affordable_holdOutWithTooShortHours_movesOnBothDimensions() {
        // Price and hours COMPOSE: the price concedes toward the demand exactly as it would
        // alone, AND the hours term pushes back to the floor, in the same counter.
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        WalkInState state = stateAt(2, 5744.0, Personality.DESPERATE); // rr=3, concession 0.40

        Decision decision = engine.evaluate(6500.0, 3, state); // 3 < FLOOR(5)

        assertEquals(Decision.Type.COUNTER, decision.type());
        assertEquals(6046.0, decision.newCounter(), 0.001, "the price leg is unchanged by the hours leg");
        assertEquals(FLOOR, decision.newHours(), "the hours leg pushes back at the floor");
        assertEquals("conceding_toward_demand+too_short", decision.reason(),
                "the reason names BOTH blocking dimensions");
    }

    @Test
    void affordable_forcedSettleAtTheLastOpenRound_regardlessOfRoll() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // would hold out if consulted alone
        WalkInState state = stateAt(4, 5744.0, Personality.AGGRESSIVE); // rr=1

        assertEquals(Decision.Type.ACCEPT, engine.evaluate(6500.0, PREFERRED, state).type(),
                "the last round it can close on its own terms must not be gambled away");

        Decision shortStay = engine.evaluate(6500.0, 1, state);
        assertEquals(Decision.Type.COUNTER, shortStay.type(),
                "forced settle with an impossible stay spends the last open round on a floor pushback");
        assertEquals(6500.0, shortStay.newCounter(), 0.001);
        assertEquals(FLOOR, shortStay.newHours());
        assertEquals("too_short", shortStay.reason());
    }

    @Test
    void concessionRateOrdersThePersonalities() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        double demand = 6500.0;

        double aggressive = engine.evaluate(demand, PREFERRED, stateAt(2, 5744.0, Personality.AGGRESSIVE)).newCounter();
        double neutral = engine.evaluate(demand, PREFERRED, stateAt(2, 5744.0, Personality.NEUTRAL)).newCounter();
        double desperate = engine.evaluate(demand, PREFERRED, stateAt(2, 5744.0, Personality.DESPERATE)).newCounter();

        assertTrue(aggressive < neutral && neutral < desperate,
                "a desperate buyer concedes fastest: " + aggressive + " < " + neutral + " < " + desperate);
    }

    // ==================== branch 5: beyond budget ====================

    @Test
    void beyondBudget_concedesTowardTheCeilingOnly_neverAcceptsTheDemand() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // roll must not rescue an unaffordable demand
        WalkInState state = stateAt(2, 5744.0, Personality.DESPERATE); // rr=3

        Decision decision = engine.evaluate(99_999.0, PREFERRED, state);

        assertEquals(Decision.Type.COUNTER, decision.type(),
                "an absurd demand must NEVER close instantly (the inverted engine's exploit)");
        // 5744 + round(0.40 * (7000-5744)) = 5744 + 502 = 6246 — toward the CEILING, not the demand.
        assertEquals(6246.0, decision.newCounter(), 0.001);
        assertTrue(decision.newCounter() <= CEILING, "the ceiling is a hard walk-away line");
        assertEquals(PREFERRED, decision.newHours());
        assertEquals("conceding_toward_ceiling", decision.reason());
    }

    @Test
    void beyondBudgetWithTooShortHours_countersOnBothDimensions() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.01);
        WalkInState state = stateAt(2, 5744.0, Personality.DESPERATE); // rr=3

        Decision decision = engine.evaluate(99_999.0, 2, state);

        assertEquals(Decision.Type.COUNTER, decision.type());
        assertEquals(6246.0, decision.newCounter(), 0.001);
        assertEquals(FLOOR, decision.newHours());
        assertEquals("conceding_toward_ceiling+too_short", decision.reason());
    }

    // ==================== the 0h ghost stays dead at the engine layer ====================

    @Test
    void zeroOrNegativeHoursNeverReachADeal_evenWithAnIrresistiblePrice() {
        // Belt to the behaviour's braces (absent-key -> standing hours): if a literal 0 or a
        // negative ever DOES reach the engine, it is below every floor (templates pin floors
        // >= 1), so it is countered up — never accepted, never closed at 0.
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // maximally accept-happy roll
        WalkInState state = stateAt(2, 5744.0, Personality.DESPERATE);

        for (int degenerate : new int[]{0, -3}) {
            Decision decision = engine.evaluate(5000.0, degenerate, state); // price <= own: a bargain
            assertEquals(Decision.Type.COUNTER, decision.type(),
                    degenerate + "h must never close a deal");
            assertEquals(FLOOR, decision.newHours(), "countered up to the floor, never below");
        }
    }

    // ==================== concession mechanics ====================

    @Test
    void concessionReachingTheDemandIsAgreementNotAnEchoCounter() {
        // Checkpoint-#5 minor 2 (WALKIN-14): 6999 -> 7000 with AGGRESSIVE 0.10 rounds to a 0
        // step; the €1 minimum reaches the demand exactly. Pre-fix this came back as a COUNTER
        // at the player's own number — "I'll pay 7000" in reply to "give me 7000". A step that
        // reaches the demand (with workable hours) is agreement.
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // hold-out roll — the CAP accepts, not the roll
        WalkInState nearClosed = stateAt(2, 6999.0, Personality.AGGRESSIVE);

        Decision decision = engine.evaluate(7000.0, PREFERRED, nearClosed);

        assertEquals(Decision.Type.ACCEPT, decision.type());
        assertEquals("concession_reached_demand", decision.reason());
        assertEquals(PREFERRED, decision.newHours());
    }

    @Test
    void aCapHitAgainstAFractionalDemandAcceptsWhenHoursWork() {
        // €0.50 apart with workable hours is a closed deal, not a haggle (checkpoint-#5 minor 2).
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        WalkInState nearClosed = stateAt(2, 6482.0, Personality.AGGRESSIVE); // step -> €1 -> 6483, overshoots

        Decision decision = engine.evaluate(6482.50, PREFERRED, nearClosed); // player typed cents

        assertEquals(Decision.Type.ACCEPT, decision.type());
        assertEquals("concession_reached_demand", decision.reason());
    }

    @Test
    void aCapHitCounterStillReturnsWholeEurosWhenTheHoursBlock() {
        // Adversarial review M1 (task 19b): when the €1-min step would overshoot a FRACTIONAL
        // demand, the cap must floor it — a bare min(toward, ...) would leak the player's cents
        // onto the wire. The counter path is now reachable at the cap only when the HOURS still
        // block the deal (the floor pushback), which is exactly where a priced counter goes out.
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        WalkInState nearClosed = stateAt(2, 6482.0, Personality.AGGRESSIVE);

        Decision decision = engine.evaluate(6482.50, 3, nearClosed); // 3 < FLOOR(5); player typed cents

        assertEquals(Decision.Type.COUNTER, decision.type());
        assertEquals(6482.0, decision.newCounter(), 0.001, "floored to the whole euro at or below the demand");
        assertEquals(Math.rint(decision.newCounter()), decision.newCounter(), "whole euros only");
        assertEquals(FLOOR, decision.newHours(), "the counter exists to carry the floor pushback");
    }

    @Test
    void repeatedIdenticalDemandsConvergeMonotonically() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        double demand = 6500.0;
        double own = 5744.0;
        for (int round = 2; round <= 3; round++) { // rr=3,2 — the pre-forced-accept rounds
            Decision decision = engine.evaluate(demand, PREFERRED, stateAt(round, own, Personality.NEUTRAL));
            assertEquals(Decision.Type.COUNTER, decision.type());
            assertTrue(decision.newCounter() > own, "each counter must rise");
            assertTrue(decision.newCounter() <= demand, "and never overshoot the demand");
            own = decision.newCounter();
        }
    }

    // ==================== determinism ====================

    @Test
    void sameSeedProducesSameDecisionSequence() {
        RealNegotiationEngine engineA = new RealNegotiationEngine(ROUND_LIMIT, new Random(777));
        RealNegotiationEngine engineB = new RealNegotiationEngine(ROUND_LIMIT, new Random(777));
        WalkInState round2 = stateAt(2, 5744.0, Personality.DESPERATE);
        WalkInState round3 = stateAt(3, 5846.0, Personality.DESPERATE);

        // The hours dimension varies across the sequence — including a too-short round — and
        // the two engines must still walk in lockstep: the roll is consumed once per
        // affordable evaluation regardless of the hours branch.
        Decision a1 = engineA.evaluate(6000.0, 3, round2);
        Decision a2 = engineA.evaluate(6200.0, 6, round3);
        Decision b1 = engineB.evaluate(6000.0, 3, round2);
        Decision b2 = engineB.evaluate(6200.0, 6, round3);

        assertEquals(a1.type(), b1.type());
        assertEquals(a1.newCounter(), b1.newCounter());
        assertEquals(a1.newHours(), b1.newHours());
        assertEquals(a2.type(), b2.type());
        assertEquals(a2.newCounter(), b2.newCounter());
        assertEquals(a2.newHours(), b2.newHours());
    }

    @Test
    void theHoursBranchDoesNotForkTheSeededRollSequence() {
        // Two engines on the same seed; one sees a too-short first round, the other a workable
        // one. The SECOND evaluation must land on the same roll for both — i.e. the too-short
        // path consumed exactly one draw, like every other affordable evaluation.
        RealNegotiationEngine engineA = new RealNegotiationEngine(ROUND_LIMIT, new Random(4242));
        RealNegotiationEngine engineB = new RealNegotiationEngine(ROUND_LIMIT, new Random(4242));
        WalkInState round2 = stateAt(2, 5744.0, Personality.NEUTRAL);
        WalkInState round3 = stateAt(3, 5846.0, Personality.NEUTRAL);

        engineA.evaluate(6000.0, 2, round2);          // too-short round (still draws once)
        engineB.evaluate(6000.0, PREFERRED, round2);  // workable round (draws once)

        Decision a2 = engineA.evaluate(6200.0, PREFERRED, round3);
        Decision b2 = engineB.evaluate(6200.0, PREFERRED, round3);

        assertEquals(a2.type(), b2.type(), "round-2 hours must not desync the round-3 roll");
        assertEquals(a2.newCounter(), b2.newCounter());
    }
}
