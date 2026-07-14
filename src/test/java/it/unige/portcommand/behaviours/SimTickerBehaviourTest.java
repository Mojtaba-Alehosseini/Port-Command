package it.unige.portcommand.behaviours;

import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit-tests the sim-ms → wall-ms period conversion in isolation (no JADE agent
 * needed). The actual {@code onTick} firing is exercised by integration in the
 * consuming tasks 06/07.
 */
class SimTickerBehaviourTest {

    @Test
    void simMinuteTickAtDefaultRate() {
        // 60_000 sim-ms = 60 sim-s -> 60*300*1000/86400 = 208 wall ms.
        assertEquals(208L, SimTickerBehaviour.wallPeriodMs(new SimClock(300), 60_000));
    }

    @Test
    void fiveSimSecondTick() {
        // 5_000 sim-ms = 5 sim-s -> 5*300*1000/86400 = 17 wall ms.
        assertEquals(17L, SimTickerBehaviour.wallPeriodMs(new SimClock(300), 5_000));
    }

    @Test
    void subSecondTickClampsToOneMillis() {
        assertEquals(1L, SimTickerBehaviour.wallPeriodMs(new SimClock(300), 100));
    }
}
