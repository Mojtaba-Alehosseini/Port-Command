package it.unige.portcommand.agents;

import it.unige.portcommand.ontology.Position;

/**
 * Pure unit-conversion + geometry for the tug fleet. The <strong>single documented
 * home</strong> of the two map/speed scale constants (task 08 §8.0):
 * {@link #PIXELS_PER_KM} (map pixels → km) and {@link #KNOT_TO_KMH} (knots → km/h).
 *
 * <p>Every tug cost/ETA/fuel/movement number routes through these methods, so the
 * bid ETA ({@code BidInCNPBehaviour}) and the per-tick transit movement
 * ({@code TransitToVesselBehaviour} via {@link TugAgent#advanceToward}) use identical
 * conversions — proven to agree by {@code TugMathTest} (simulated arrival time ≈
 * quoted {@code etaMinutes}).
 *
 * <p>Order of conversion, always and everywhere: (1) pixels→km via
 * {@link #PIXELS_PER_KM} at the single boundary, THEN (2) knots→km/h via
 * {@link #KNOT_TO_KMH}. Raw map pixels never reach a cost/ETA/fuel formula.
 */
public final class TugMath {

    /** Map scale: 10 map-pixels = 1&nbsp;km (demo value; defined ONLY here). */
    public static final double PIXELS_PER_KM = 10.0;

    /** 1 knot = 1.852&nbsp;km/h, exact (nautical mile ÷ 1000). */
    public static final double KNOT_TO_KMH = 1.852;

    /** Arrival tolerance (km): a movement leg is "arrived" once the gap drops below this. */
    public static final double ARRIVAL_EPS_KM = 1.0e-6;

    private TugMath() {
    }

    /**
     * Straight-line map distance between two positions, converted pixels→km — the
     * single conversion boundary. Downstream cost/ETA/fuel formulas take the km result.
     */
    public static double distanceKm(Position from, Position to) {
        double dxPx = to.x() - from.x();
        double dyPx = to.y() - from.y();
        return Math.hypot(dxPx, dyPx) / PIXELS_PER_KM;
    }

    /**
     * Minutes to cover {@code distanceKm} at {@code topSpeedKnots}: knots→km/h first
     * ({@code topSpeedKnots * KNOT_TO_KMH} is km/h), then hours→minutes. NOT
     * {@code distanceKm / topSpeedKnots * 60} — that would drop the 1.852 factor.
     */
    public static double etaMinutes(double distanceKm, double topSpeedKnots) {
        return distanceKm / (topSpeedKnots * KNOT_TO_KMH) * 60.0;
    }

    /**
     * Km travelled in {@code dtSimSeconds} at {@code topSpeedKnots} — the SAME
     * knots→km/h conversion as {@link #etaMinutes}, so integrating this over the ETA
     * sim-time exactly covers the route ({@code stepKm} summed = {@code distanceKm}
     * when elapsed sim-time = {@code etaMinutes}).
     */
    public static double stepKm(double topSpeedKnots, double dtSimSeconds) {
        return topSpeedKnots * KNOT_TO_KMH * (dtSimSeconds / 3600.0);
    }

    /**
     * A new position {@code stepPx} pixels along the straight line {@code from}→{@code to},
     * clamped to {@code to} (never overshoots). Heading is set to the bearing of travel
     * (degrees clockwise from north, screen coords with y increasing downward) for the
     * GUI (task 18); the sim ignores heading.
     */
    public static Position moveToward(Position from, Position to, double stepPx) {
        double dxPx = to.x() - from.x();
        double dyPx = to.y() - from.y();
        double distPx = Math.hypot(dxPx, dyPx);
        double headingDeg = bearingDeg(dxPx, dyPx, from.headingDeg());
        if (distPx <= 1.0e-9 || stepPx >= distPx) {
            return new Position(to.x(), to.y(), headingDeg);
        }
        double f = stepPx / distPx;
        return new Position(from.x() + dxPx * f, from.y() + dyPx * f, headingDeg);
    }

    /** Bearing (deg clockwise from north) of screen-vector (dx,dy); keeps the prior heading if stationary. */
    private static double bearingDeg(double dxPx, double dyPx, double priorHeadingDeg) {
        if (Math.hypot(dxPx, dyPx) <= 1.0e-9) {
            return priorHeadingDeg;
        }
        double deg = Math.toDegrees(Math.atan2(dxPx, -dyPx));
        return (deg % 360.0 + 360.0) % 360.0;
    }
}
