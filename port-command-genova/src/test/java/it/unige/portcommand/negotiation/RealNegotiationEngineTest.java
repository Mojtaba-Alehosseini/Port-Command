package it.unige.portcommand.negotiation;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Table-driven coverage of {@link RealNegotiationEngine}'s branch logic (task 15). Uses
 * {@link WalkInState}'s canonical constructor directly to pin an exact {@code round} per case —
 * simpler than chaining {@code recordPlayerCounter} calls — and a fixed-return {@link Random}
 * subclass to control the personality roll deterministically without needing to reverse-engineer
 * a seed's draw sequence.
 */
class RealNegotiationEngineTest {

    private static final int ROUND_LIMIT = 4;
    private static final double MIN = 5000.0;
    private static final double TARGET = 8000.0;

    /** {@code roundsRemaining = ROUND_LIMIT + 1 - round}, so round=2..5 gives rr=3,2,1,0. */
    private static WalkInState stateAtRound(int round, double min, double target, Personality personality) {
        return new WalkInState("cargo_vessel", min, target, personality, 120, round, 0L, 0.0, 0.0, 8);
    }

    private static RealNegotiationEngine engineWithFixedRoll(double fixedRoll) {
        return new RealNegotiationEngine(ROUND_LIMIT, new Random() {
            @Override
            public double nextDouble() {
                return fixedRoll;
            }
        });
    }

    // ==================== branch 1: price >= target ====================

    @Test
    void priceAtOrAboveTarget_alwaysAccepts() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // would miss every threshold if consulted
        WalkInState state = stateAtRound(2, MIN, TARGET, Personality.AGGRESSIVE); // roundsRemaining=3

        assertEquals(Decision.Type.ACCEPT, engine.evaluate(TARGET, state).type());
        assertEquals(Decision.Type.ACCEPT, engine.evaluate(TARGET + 500, state).type());
    }

    // ==================== branch 2: rounds exhausted (both sub-branches) ====================

    @Test
    void roundsExhausted_belowMinimum_withdrawsOverPriced() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // would accept if roll were consulted
        WalkInState state = stateAtRound(5, MIN, TARGET, Personality.NEUTRAL); // roundsRemaining=0

        Decision decision = engine.evaluate(MIN - 500, state);

        assertEquals(Decision.Type.WITHDRAW, decision.type());
        // Pinned exactly: WithdrawalBehaviour.outcomeFor switches on this literal string to
        // record the canonical Deal.Outcome.WITHDRAW_PRICE -- a drift here would throw
        // IllegalArgumentException at runtime instead of failing this test.
        assertEquals("over_priced", decision.reason());
    }

    @Test
    void roundsExhausted_inBand_acceptsGracefully() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // would counter/miss if roll were consulted
        WalkInState state = stateAtRound(5, MIN, TARGET, Personality.NEUTRAL); // roundsRemaining=0

        Decision decision = engine.evaluate(MIN + 500, state); // in [MIN, TARGET)

        assertEquals(Decision.Type.ACCEPT, decision.type());
    }

    // ==================== branch 3: price < minimum, rounds remain ====================

    @Test
    void belowMinimum_countersAtMidpointScaledByPersonality_whenRoundsRemain() {
        WalkInState state = stateAtRound(2, MIN, TARGET, Personality.AGGRESSIVE); // roundsRemaining=3
        RealNegotiationEngine engine = engineWithFixedRoll(0.5);

        Decision decision = engine.evaluate(MIN - 1000, state);

        assertEquals(Decision.Type.COUNTER, decision.type());
        // (5000+8000)/2 * 1.10 = 7150, well within [MIN, TARGET*1.3] -- unclamped.
        assertEquals(7150.0, decision.newCounter(), 0.001);
    }

    @Test
    void belowMinimum_personalityModifierVariesTheCounter() {
        WalkInState round2 = stateAtRound(2, MIN, TARGET, Personality.NEUTRAL);
        WalkInState round2Desperate = stateAtRound(2, MIN, TARGET, Personality.DESPERATE);
        RealNegotiationEngine engine = engineWithFixedRoll(0.5);

        assertEquals(6500.0, engine.evaluate(MIN - 1000, round2).newCounter(), 0.001); // mod 1.00
        assertEquals(6175.0, engine.evaluate(MIN - 1000, round2Desperate).newCounter(), 0.001); // mod 0.95
    }

    @Test
    void belowMinimum_atLastOpenRound_stillCounters_doesNotForceWithdrawEarly() {
        // Deliberate design choice (see RealNegotiationEngine's class javadoc): withdrawal only
        // fires once roundsRemaining<=0, not at roundsRemaining==1 -- the forced-resolution rule
        // at rr==1 applies only to the in-band branch. This pins that choice against regression.
        WalkInState state = stateAtRound(4, MIN, TARGET, Personality.NEUTRAL); // roundsRemaining=1
        RealNegotiationEngine engine = engineWithFixedRoll(0.5);

        Decision decision = engine.evaluate(MIN - 1000, state);

        assertEquals(Decision.Type.COUNTER, decision.type());
    }

    // ==================== branch 4: in-band ====================

    @Test
    void inBand_rollBelowThreshold_accepts() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.01); // below every personality's threshold
        WalkInState state = stateAtRound(2, MIN, TARGET, Personality.DESPERATE); // roundsRemaining=3, threshold 0.80

        assertEquals(Decision.Type.ACCEPT, engine.evaluate(6000.0, state).type());
    }

    @Test
    void inBand_rollAboveThreshold_countersWithUpPct_notTheBelowMinimumFormula() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // above every personality's threshold
        WalkInState state = stateAtRound(2, MIN, TARGET, Personality.NEUTRAL); // roundsRemaining=3

        Decision decision = engine.evaluate(7000.0, state);

        assertEquals(Decision.Type.COUNTER, decision.type());
        // 7000 * (1 + 0.04) = 7280 -- NEUTRAL's COUNTER_UP_PCT, not the below-minimum midpoint
        // formula (which would give (5000+8000)/2*1.00 = 6500, a lower and wrong value here).
        assertEquals(7280.0, decision.newCounter(), 0.001);
    }

    @Test
    void inBand_forcedAcceptAtLastRound_regardlessOfRoll() {
        RealNegotiationEngine engine = engineWithFixedRoll(0.99); // would miss every threshold
        WalkInState state = stateAtRound(4, MIN, TARGET, Personality.AGGRESSIVE); // roundsRemaining=1

        assertEquals(Decision.Type.ACCEPT, engine.evaluate(6000.0, state).type());
    }

    // ==================== clamp ====================

    @Test
    void counterNeverExceedsTargetTimes1Point3_evenUnderAPathologicalMinTargetGap() {
        // With today's fixed personality modifiers (max 1.10 for the below-minimum formula, max
        // 0.08 for the in-band formula) the upper clamp is mathematically unreachable for any
        // sane MIN<=TARGET pair -- the raw ideal never exceeds TARGET*1.10. To exercise the
        // clamp's actual ceiling-enforcement code path, this constructs a deliberately degenerate
        // state (MIN > TARGET) that pushes the raw formula above TARGET*1.3, and asserts the
        // engine still returns exactly the clamped ceiling.
        double pathologicalMin = 9000.0;
        double smallTarget = 1000.0;
        WalkInState state = stateAtRound(2, pathologicalMin, smallTarget, Personality.AGGRESSIVE);
        RealNegotiationEngine engine = engineWithFixedRoll(0.5);

        // Must stay below BOTH min and target so branch order doesn't short-circuit to ACCEPT
        // (playerPrice >= targetPrice is checked first) before ever reaching the below-minimum
        // counter branch this test means to exercise.
        Decision decision = engine.evaluate(500.0, state);

        assertEquals(Decision.Type.COUNTER, decision.type());
        assertEquals(smallTarget * 1.3, decision.newCounter(), 0.001);
    }

    // ==================== determinism ====================

    @Test
    void sameSeedProducesSameDecisionSequence() {
        RealNegotiationEngine engineA = new RealNegotiationEngine(ROUND_LIMIT, new Random(777));
        RealNegotiationEngine engineB = new RealNegotiationEngine(ROUND_LIMIT, new Random(777));
        WalkInState round2 = stateAtRound(2, MIN, TARGET, Personality.DESPERATE);
        WalkInState round3 = stateAtRound(3, MIN, TARGET, Personality.DESPERATE);

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
