package it.unige.portcommand.harbourmaster.financial;

import java.util.Map;

/**
 * Minimal constants-only stub (v1.1) — created here because task 10 (Phase 2) needs a static
 * fair-price band before task 20 (Phase 5) exists; task 20 refines and owns final calibration
 * (berth_base, surcharges, etc. — see {@code planning/20_financial_dashboard.md}).
 *
 * <p>Canonical walk-in economy bands, {@code PROJECT_DEFINITION.md} §7.5 (single source of
 * truth; {@code vessel_templates.json} must match — enforced by {@code VesselTemplatesTest}).
 */
public final class IncomeRules {

    /** A closed [lo, hi] € band. */
    public record PriceRange(double lo, double hi) {

        public double midpoint() {
            return (lo + hi) / 2.0;
        }
    }

    // §7.5: min_acceptable-range lo .. target-range hi — the full plausible-deal envelope for
    // each vessel type. This is PUBLIC game-design knowledge (the canonical fee table), not any
    // individual vessel's hidden min/target draw, so the Assistant may read it (v1.1 P-04
    // forbids reading a *specific vessel's* WalkInState, not the static market-wide table).
    private static final Map<String, PriceRange> FEE_RANGES = Map.of(
            "cargo_vessel", new PriceRange(1_400, 2_200),
            "container_vessel", new PriceRange(1_800, 3_500),
            "tanker", new PriceRange(4_000, 8_000),
            "ferry", new PriceRange(1_100, 2_000),
            "cruise_ship", new PriceRange(3_200, 5_500));

    private IncomeRules() {
    }

    /**
     * Static fair-price band for {@code vesselType} (PROJECT_DEFINITION §7.5), used by the
     * Assistant when {@code MarketHistoryArtifact} sample count is too low to trust (&lt; 10).
     * {@code berthId} is accepted for a future per-berth premium (task 20) but unused by this
     * minimal stub — every berth uses the same type-keyed band today.
     */
    public static PriceRange berthFeeRange(String berthId, String vesselType) {
        PriceRange range = FEE_RANGES.get(vesselType);
        if (range == null) {
            throw new IllegalArgumentException("unknown vessel type: " + vesselType);
        }
        return range;
    }
}
