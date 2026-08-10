package it.unige.portcommand.agents;

import it.unige.portcommand.core.Settings;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.negotiation.WalkInState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link WalkInVesselAgent#roundsRemaining(int, WalkInState)} against the configurable
 * {@code Settings.roundLimit()} range (audit P1-10, closing the 2026-07-17 TODO).
 *
 * <p>The accessor used to compute from a hardcoded {@code MAX_ROUNDS = 4} while
 * {@code RealNegotiationEngine} took the same limit from {@code Settings} at both spawn sites, so
 * the two silently disagreed for any configured limit but 4. {@code Settings.load()} is a
 * JVM-lifetime cache, so the arithmetic is pinned through the package-private static overload
 * rather than by mutating that cache.
 */
class WalkInVesselAgentRoundsTest {

    private static WalkInState atRound(int round) {
        WalkInState state = WalkInState.initial("cargo_vessel", 2000.0, 3000.0,
                Personality.NEUTRAL, 300, 6, 4);
        // round starts at 1; recordPlayerCounter owns the increment (ADR-09 decision 1).
        for (int i = 1; i < round; i++) {
            state = state.recordPlayerCounter(2500.0, 6);
        }
        return state;
    }

    @ParameterizedTest(name = "roundLimit={0} at round 1 leaves {0} rounds")
    @CsvSource({"2", "4", "8"})
    void anUnstartedNegotiationHasTheWholeConfiguredBudget(int roundLimit) {
        assertEquals(roundLimit, WalkInVesselAgent.roundsRemaining(roundLimit, atRound(1)),
                "round 1 has not consumed anything yet (limit + 1 - 1)");
    }

    @Test
    void theLowerBoundOfTheConfiguredRangeExhaustsAfterTwoRounds() {
        assertEquals(2, WalkInVesselAgent.roundsRemaining(2, atRound(1)));
        assertEquals(1, WalkInVesselAgent.roundsRemaining(2, atRound(2)));
        assertEquals(0, WalkInVesselAgent.roundsRemaining(2, atRound(3)));
        assertEquals(0, WalkInVesselAgent.roundsRemaining(2, atRound(5)),
                "never negative once the budget is spent");
    }

    @Test
    void theUpperBoundOfTheConfiguredRangeTracksAllTheWayDown() {
        assertEquals(8, WalkInVesselAgent.roundsRemaining(8, atRound(1)));
        assertEquals(5, WalkInVesselAgent.roundsRemaining(8, atRound(4)));
        assertEquals(0, WalkInVesselAgent.roundsRemaining(8, atRound(9)));
    }

    @Test
    void theTwoBoundsDisagreeSoTheLimitIsGenuinelyLoadBearing() {
        // Non-vacuity: with the old hardcoded 4 both of these returned the same number.
        assertTrue(WalkInVesselAgent.roundsRemaining(2, atRound(2))
                        != WalkInVesselAgent.roundsRemaining(8, atRound(2)),
                "roundLimit must actually change the answer");
    }

    @Test
    void aNullStateFallsBackToTheFullConfiguredLimit() {
        assertEquals(2, WalkInVesselAgent.roundsRemaining(2, null));
        assertEquals(8, WalkInVesselAgent.roundsRemaining(8, null));
    }

    @Test
    void theShippedDefaultIsInsideTheValidatedRange() {
        int shipped = Settings.load().roundLimit();
        assertTrue(shipped >= 2 && shipped <= 8, "defaults.json roundLimit out of 2..8: " + shipped);
    }
}
