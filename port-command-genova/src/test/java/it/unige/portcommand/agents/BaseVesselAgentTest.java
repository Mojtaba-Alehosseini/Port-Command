package it.unige.portcommand.agents;

import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure arrival-delay conversion (sim-time ETA -> wall-ms WakerBehaviour delay). */
class BaseVesselAgentTest {

    @Test
    void arrivalDelayIsZeroWhenEtaAlreadyDue() {
        SimClock clock = new SimClock(300);
        assertEquals(0L, BaseVesselAgent.arrivalDelayWallMs(0L, 0L, clock));
        assertEquals(0L, BaseVesselAgent.arrivalDelayWallMs(-5_000L, 0L, clock));
    }

    @Test
    void arrivalDelayConvertsFutureEtaToWallMs() {
        SimClock clock = new SimClock(300); // 300 real-s per game day
        long expected = clock.simSecondsToWallMs(120); // 2 sim-minutes ahead
        long actual = BaseVesselAgent.arrivalDelayWallMs(120_000L, 0L, clock);
        assertEquals(expected, actual);
        assertTrue(actual > 0);
    }
}
