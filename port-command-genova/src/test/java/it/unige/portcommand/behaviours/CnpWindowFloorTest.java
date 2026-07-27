// NOTE: this test deliberately does NOT live in it.unige.portcommand.behaviours.cnp.
// BehaviourCatalogueTest resolves each of the five behaviour subpackages through the
// classloader and counts the .class files it finds; a test class in one of those packages
// shadows the main-tree directory and breaks the locked 51-behaviour count (ADR-01).
// SimTickerBehaviourTest sits here for the same reason.
package it.unige.portcommand.behaviours;

import it.unige.portcommand.behaviours.cnp.InitiateCNPBehaviour;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit A-03 (2026-07-27): the CFP bid window and the CNP retry delay were the only sim→wall
 * conversions in the codebase WITHOUT a floor — {@code SimTickerBehaviour.wallPeriodMs},
 * {@code RefuelIfLowBehaviour} and {@code PoissonSpawnBehaviour.nextGapWallMs} all apply one.
 * At the sandbox default (300 real-seconds per game day) the 5-sim-second window came to 17 ms:
 * every tug had to receive the CFP, score a bid, reply, AND the hub's collector had to run,
 * inside 17 ms. Under {@code --day-length 17} or below, integer division floored it to a literal
 * <b>0</b> — the waker fired on the next scheduler pass and {@code closeAndAward()} ran with zero
 * bids, every time. The retry delay collapsed with it: 30 sim-seconds is 5 ms at
 * {@code --day-length 17} and 1 ms at {@code --day-length 5}, so all three retries were spent as
 * fast as the scheduler could run them.
 *
 * <p><b>The floor is deliberately 50 ms, not the 250 ms the audit suggested.</b> The audit sized
 * it against the storm's 1.25 s channel transit; the binding constraint is tighter than that. In
 * {@code storm.json} the tanker SPAWNS at t=960 s and the wind crosses 30 kn at t=990 s — 30
 * sim-seconds, i.e. <b>312 ms</b> of wall clock at 900 s/day, and that is an UPPER BOUND on the
 * award's real margin (the grant still has to come through the arrival REQUEST, a DF resolve and
 * the Prolog gate). The CANCEL beat only exists if the tugs are awarded inside that gap, so a
 * 250 ms window would eat most of the margin task 26 retimed (BUG-02). 50 ms sits BELOW the
 * smallest window any shipped scenario already uses (52 ms at 900 s/day), so every scenario timing
 * is provably unchanged and only the two pathological pacings move.
 */
class CnpWindowFloorTest {

    @Test
    void theShippedScenarioPacingsAreLeftExactlyAsTheyWere() {
        // 900 s/day (busy_day, storm): 5*900*1000/86400 = 52 ms — above the floor, untouched.
        assertEquals(52L, InitiateCNPBehaviour.cnpWindowWallMs(new SimClock(900), 5));
        // 1800 s/day (tutorial): 104 ms — untouched.
        assertEquals(104L, InitiateCNPBehaviour.cnpWindowWallMs(new SimClock(1800), 5));
        // The retry delay (30 sim-s) at the same pacings is far above the floor too.
        assertEquals(312L, InitiateCNPBehaviour.cnpWindowWallMs(new SimClock(900), 30));
    }

    @Test
    void theSandboxDefaultNoLongerCollapsesToSeventeenMillis() {
        assertEquals(17L, new SimClock(300).simSecondsToWallMs(5), "the unfloored value, for the record");
        assertEquals(50L, InitiateCNPBehaviour.cnpWindowWallMs(new SimClock(300), 5));
    }

    @Test
    void aFastClockCanNeverProduceAZeroLengthWindow() {
        // --day-length 5 is the value Main's own javadoc names for integration runs: the
        // unfloored conversion is a literal 0, so the waker fired before any tug could bid.
        assertEquals(0L, new SimClock(5).simSecondsToWallMs(5), "the unfloored value, for the record");
        assertTrue(InitiateCNPBehaviour.cnpWindowWallMs(new SimClock(5), 5) > 0,
                "a zero-length bid window awards nothing, always");
        assertEquals(50L, InitiateCNPBehaviour.cnpWindowWallMs(new SimClock(5), 5));
        assertEquals(50L, InitiateCNPBehaviour.cnpWindowWallMs(new SimClock(17), 5));
    }

    @Test
    void theFloorStaysBelowEveryShippedScenarioWindow() {
        // Guards the reasoning above: if someone raises the floor past 52 ms, the storm's
        // grant-to-wind margin starts shrinking and BUG-02's retiming is back in play.
        assertTrue(InitiateCNPBehaviour.MIN_CNP_WINDOW_WALL_MS < new SimClock(900).simSecondsToWallMs(5),
                "the floor must not perturb any shipped scenario's CFP window");
    }
}
