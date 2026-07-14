package it.unige.portcommand.agents;

import it.unige.portcommand.ontology.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the single conversion boundary and — the load-bearing case — proves the bid
 * ETA and the per-tick transit movement use the SAME conversion, so a tug arrives in
 * (approximately) the {@code eta_minutes} it quoted. If someone drops the 1.852 knot
 * factor or the pixel scale from one side but not the other, {@code arrivalAgreesWithQuotedEta}
 * breaks.
 */
class TugMathTest {

    private static final double TOP_SPEED_KNOTS = 12.0;

    @Test
    void distanceKmConvertsPixelsAtTheScale() {
        // 100 px horizontally at 10 px/km = 10 km.
        assertEquals(10.0, TugMath.distanceKm(new Position(0, 0, 0), new Position(100, 0, 0)), 1e-9);
        assertEquals(602.0781 / TugMath.PIXELS_PER_KM,
                TugMath.distanceKm(new Position(50, 100, 0), new Position(500, 500, 0)), 1e-3);
    }

    @Test
    void etaMinutesConvertsKnotsToKmhFirst() {
        // Hard-coded literals (NOT KNOT_TO_KMH-derived) so a wrong constant can't hide:
        // 12 kn * 1.852 = 22.224 km/h, so a 22.224 km route is exactly 60 min.
        assertEquals(60.0, TugMath.etaMinutes(22.224, 12.0), 1e-3);
        assertEquals(1.852, TugMath.KNOT_TO_KMH, 0.0, "knot->km/h factor is exact");
    }

    @Test
    void stepKmIsDistancePerSimSecondAtTopSpeed() {
        // In one hour (3600 s) the tug covers exactly 12 kn * 1.852 = 22.224 km (literal).
        assertEquals(22.224, TugMath.stepKm(12.0, 3600.0), 1e-3);
        // And PIXELS_PER_KM is the pinned pixel scale.
        assertEquals(10.0, TugMath.PIXELS_PER_KM, 0.0);
    }

    @Test
    void moveTowardClampsAndNeverOvershoots() {
        Position from = new Position(0, 0, 0);
        Position to = new Position(100, 0, 0);
        assertEquals(30.0, TugMath.moveToward(from, to, 30.0).x(), 1e-9);
        Position clamped = TugMath.moveToward(from, to, 500.0); // step > distance
        assertEquals(100.0, clamped.x(), 1e-9);
        assertEquals(0.0, clamped.y(), 1e-9);
    }

    @Test
    void arrivalAgreesWithQuotedEta() {
        Position start = new Position(50, 100, 0);
        Position target = new Position(500, 500, 0);

        double distanceKm = TugMath.distanceKm(start, target);
        double quotedEtaMinutes = TugMath.etaMinutes(distanceKm, TOP_SPEED_KNOTS);

        // Replay the exact per-tick loop TugAgent.advanceToward runs, one sim-second per tick.
        Position pos = start;
        long ticks = 0;
        while (TugMath.distanceKm(pos, target) > TugMath.ARRIVAL_EPS_KM) {
            double remainingKm = TugMath.distanceKm(pos, target);
            double stepKm = Math.min(TugMath.stepKm(TOP_SPEED_KNOTS, 1.0), remainingKm);
            pos = TugMath.moveToward(pos, target, stepKm * TugMath.PIXELS_PER_KM);
            if (++ticks > 1_000_000) {
                fail("transit did not converge — step/distance units disagree");
            }
        }
        double simulatedArrivalMinutes = ticks / 60.0; // one tick = one sim-second

        // The integer-tick loop can only overshoot the continuous ETA by at most one tick
        // (1 sim-second = 1/60 min); it must never arrive early.
        assertTrue(simulatedArrivalMinutes >= quotedEtaMinutes - 1e-9,
                "arrived before the quoted ETA: " + simulatedArrivalMinutes + " < " + quotedEtaMinutes);
        assertTrue(simulatedArrivalMinutes <= quotedEtaMinutes + 1.0 / 60.0 + 1e-9,
                "arrival lags the quoted ETA by more than one tick: "
                        + simulatedArrivalMinutes + " vs " + quotedEtaMinutes);
    }
}
