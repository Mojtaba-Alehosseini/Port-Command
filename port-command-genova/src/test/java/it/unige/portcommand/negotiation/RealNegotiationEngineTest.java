package it.unige.portcommand.negotiation;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven coverage of {@link RealNegotiationEngine}'s buyer-semantics branch table
 * (task 19 rewrite — see the engine's class javadoc for why the original task-15 table was
 * inverted and which documents mandate this direction). Uses {@link WalkInState}'s canonical
 * constructor directly to pin an exact {@code round} and {@code lastOwnOffer} per case, and a
 * fixed-return {@link Random} subclass to control the personality roll deterministically.
 */
class RealNegotiationEngineTest {

    private static final int ROUND_LIMIT = 4;
    private static final double CEILING = 7000.0; // targetPrice = the vessel's hard budget
    private static final double MIN = 4500.0;     // sampled but engine-unused (task-20 data)

    /** {@code roundsRemaining = ROUND_LIMIT + 1 - round}, so round=2..5 gives rr=3,2,1,0. */
    private static WalkInState stateAt(int round, double ownOffer, Personality personality) {
        return new WalkInState("cargo_vessel", MIN, CEILING, personality, 120, round, 0L, 0.0, ownOffer, 8);
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
    void demandAtOrBelowTheVesselsOwnOffer_acceptsImmediately() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // roll must not be consulted
        WalkInState state = stateAt(2, 5744.0, Personality.AGGRESSIVE); // rr=3

        assertEquals(Decision.Type.ACCEPT, engine.evaluate(5744.0, state).type(),
                "a demand equal to the standing offer is already agreed");
        assertEquals(Decision.Type.ACCEPT, engine.evaluate(5000.0, state).type(),
                "a demand BELOW the standing offer is a bargain for the buyer — instant deal");
    }

    // ==================== branch 2: rounds exhausted ====================

    @Test
    void roundsExhausted_affordableDemand_settles() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        WalkInState state = stateAt(5, 5744.0, Personality.NEUTRAL); // rr=0

        assertEquals(Decision.Type.ACCEPT, engine.evaluate(CEILING, state).type(),
                "at the last call an affordable (<= ceiling) demand is taken");
    }

    @Test
    void roundsExhausted_demandBeyondBudget_withdrawsOverPriced() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // would accept if roll were consulted
        WalkInState state = stateAt(5, 5744.0, Personality.DESPERATE); // rr=0

        Decision decision = engine.evaluate(CEILING + 1, state);

        assertEquals(Decision.Type.WITHDRAW, decision.type());
        // Pinned exactly: WithdrawalBehaviour.outcomeFor switches on this literal string to
        // record the canonical Deal.Outcome.WITHDRAW_PRICE — and under buyer semantics the
        // name finally means what it says: the player priced the vessel out.
        assertEquals("over_priced", decision.reason());
    }

    // ==================== branch 3: affordable, rounds remain ====================

    @Test
    void affordable_rollBelowThreshold_acceptsTheDemand() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // below every personality threshold
        WalkInState state = stateAt(2, 5744.0, Personality.AGGRESSIVE); // rr=3, threshold 0.30

        assertEquals(Decision.Type.ACCEPT, engine.evaluate(6500.0, state).type());
    }

    @Test
    void affordable_rollAboveThreshold_concedesUpward_neverPastTheDemand() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // above every personality threshold
        WalkInState state = stateAt(2, 5744.0, Personality.DESPERATE); // rr=3, concession 0.40

        Decision decision = engine.evaluate(6500.0, state);

        assertEquals(Decision.Type.COUNTER, decision.type());
        // 5744 + round(0.40 * (6500-5744)) = 5744 + 302 = 6046: strictly above its own last
        // offer (visible progress), strictly below the demand (a buyer approaches from below).
        assertEquals(6046.0, decision.newCounter(), 0.001);
        assertTrue(decision.newCounter() > 5744.0 && decision.newCounter() < 6500.0);
    }

    @Test
    void affordable_forcedAcceptAtTheLastOpenRound_regardlessOfRoll() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // would hold out if consulted alone
        WalkInState state = stateAt(4, 5744.0, Personality.AGGRESSIVE); // rr=1

        assertEquals(Decision.Type.ACCEPT, engine.evaluate(6500.0, state).type(),
                "the last round it can close on its own terms must not be gambled away");
    }

    @Test
    void concessionRateOrdersThePersonalities() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        double demand = 6500.0;

        double aggressive = engine.evaluate(demand, stateAt(2, 5744.0, Personality.AGGRESSIVE)).newCounter();
        double neutral = engine.evaluate(demand, stateAt(2, 5744.0, Personality.NEUTRAL)).newCounter();
        double desperate = engine.evaluate(demand, stateAt(2, 5744.0, Personality.DESPERATE)).newCounter();

        assertTrue(aggressive < neutral && neutral < desperate,
                "a desperate buyer concedes fastest: " + aggressive + " < " + neutral + " < " + desperate);
    }

    // ==================== branch 4: beyond budget ====================

    @Test
    void beyondBudget_concedesTowardTheCeilingOnly_neverAcceptsTheDemand() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // roll must not rescue an unaffordable demand
        WalkInState state = stateAt(2, 5744.0, Personality.DESPERATE); // rr=3

        Decision decision = engine.evaluate(99_999.0, state);

        assertEquals(Decision.Type.COUNTER, decision.type(),
                "an absurd demand must NEVER close instantly (the inverted engine's exploit)");
        // 5744 + round(0.40 * (7000-5744)) = 5744 + 502 = 6246 — toward the CEILING, not the demand.
        assertEquals(6246.0, decision.newCounter(), 0.001);
        assertTrue(decision.newCounter() <= CEILING, "the ceiling is a hard walk-away line");
    }

    // ==================== concession mechanics ====================

    @Test
    void concessionAlwaysMakesWholeEuroProgressAndCapsAtTheTarget() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        // A near-closed gap: 6999 -> 7000 with AGGRESSIVE 0.10 rounds to a 0 step; the €1
        // minimum keeps visible progress, and the cap stops exactly at the demand.
        WalkInState nearClosed = stateAt(2, 6999.0, Personality.AGGRESSIVE);

        Decision decision = engine.evaluate(7000.0, nearClosed);

        assertEquals(Decision.Type.COUNTER, decision.type());
        assertEquals(7000.0, decision.newCounter(), 0.001, "min €1 step, capped at the demand");
        assertEquals(Math.rint(decision.newCounter()), decision.newCounter(), "whole euros only");
    }

    @Test
    void aCapHitAgainstAFractionalDemandStillReturnsWholeEuros() {
        // Adversarial review M1: when the €1-min step would overshoot a FRACTIONAL demand, the cap
        // must floor it — a bare min(toward, ...) would leak the player's cents onto the wire.
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        WalkInState nearClosed = stateAt(2, 6482.0, Personality.AGGRESSIVE); // step -> €1 -> 6483, overshoots

        Decision decision = engine.evaluate(6482.50, nearClosed); // player typed cents

        assertEquals(Decision.Type.COUNTER, decision.type());
        assertEquals(6482.0, decision.newCounter(), 0.001, "floored to the whole euro at or below the demand");
        assertEquals(Math.rint(decision.newCounter()), decision.newCounter(), "whole euros only");
    }

    @Test
    void repeatedIdenticalDemandsConvergeMonotonically() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99);
        double demand = 6500.0;
        double own = 5744.0;
        for (int round = 2; round <= 3; round++) { // rr=3,2 — the pre-forced-accept rounds
            Decision decision = engine.evaluate(demand, stateAt(round, own, Personality.NEUTRAL));
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

        Decision a1 = engineA.evaluate(6000.0, round2);
        Decision a2 = engineA.evaluate(6200.0, round3);
        Decision b1 = engineB.evaluate(6000.0, round2);
        Decision b2 = engineB.evaluate(6200.0, round3);

        assertEquals(a1.type(), b1.type());
        assertEquals(a1.newCounter(), b1.newCounter());
        assertEquals(a2.type(), b2.type());
        assertEquals(a2.newCounter(), b2.newCounter());
    }
}
