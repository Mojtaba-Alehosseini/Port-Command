package it.unige.portcommand.harbourmaster.financial;

import java.util.Map;

import it.unige.portcommand.harbourmaster.IncomeEvent;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.util.SimClock;

/**
 * Every way the port earns money (task 20 — owns this class; it began life as a constants-only
 * task-10 stub for {@code berthFeeRange}).
 *
 * <p>Pure arithmetic, deliberately Java rather than Prolog (CLAUDE.md rule 4 / INVARIANTS — the
 * financial helpers stay out of the 30-rule kernel). Each method computes ONE line; nothing here
 * touches a ledger, publishes an event, or reads mutable state. {@link #aggregateForDay} is the
 * one read-only exception and it only sums history back.
 *
 * <p>Canonical fee bands are {@code PROJECT_DEFINITION} §7.5 (single source of truth;
 * {@code vessel_templates.json} must match — enforced by {@code VesselTemplatesTest}). Surcharge
 * figures are {@code planning/20}'s "Hard constraints" table, which is where those numbers now
 * live: the "§3.12" the planning file cites throughout <strong>does not exist</strong> —
 * PROJECT_DEFINITION has only §3.1–§3.3 (verified 2026-07-17), and the tables were inlined into
 * planning/20 itself. See the task-20 Done note.
 */
public final class IncomeRules {

    /** A closed [lo, hi] € band. */
    public record PriceRange(double lo, double hi) {

        public double midpoint() {
            return (lo + hi) / 2.0;
        }
    }

    /** Source tags for {@link IncomeEvent#source()} — the ledger's audit vocabulary. */
    public static final String SOURCE_BERTH_BASE = "berth_base";
    public static final String SOURCE_EXTRA_TUG = "extra_tug_surcharge";
    public static final String SOURCE_HAZMAT = "hazmat_surcharge";
    public static final String SOURCE_PILOT = "pilot_service";
    public static final String SOURCE_BUNKER_FUEL = "bunker_fuel";
    public static final String SOURCE_WATER_WASTE = "water_waste";
    public static final String SOURCE_PREMIUM = "premium_surcharge";

    /** planning/20: extra_tug_surcharge +€500 per tug beyond the first. */
    private static final double EXTRA_TUG_EUR = 500.0;
    /** planning/20: hazmat_surcharge +€500. */
    private static final double HAZMAT_EUR = 500.0;
    /** planning/20: pilot_service flat +€300. */
    private static final double PILOT_EUR = 300.0;
    /** planning/20: water_waste flat +€150. */
    private static final double WATER_WASTE_EUR = 150.0;
    /** planning/20: premium_surcharge +15% of the base total, gated on reputation. */
    private static final double PREMIUM_PCT = 0.15;
    /** planning/20: the reputation at or above which premium pricing unlocks. */
    public static final double PREMIUM_REPUTATION_THRESHOLD = 80.0;

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
     * The berth fee for a served vessel — the negotiated walk-in price, or the contract's agreed
     * fee for a contracted vessel. A passthrough of {@code dealPrice}: the price is settled by the
     * time it gets here (the negotiation engine for a walk-in, {@code contracts.json} for a
     * contract), and this class does not re-price it.
     *
     * <p><strong>The task-19b seam (2026-07-17; RESOLVED by 19b the same day).</strong>
     * {@code hours} is accepted and deliberately IGNORED — fees stay per-deal FLAT amounts.
     * 19b made duration a real §7.3 bargaining dimension (the {@code hours} arriving here is
     * now genuinely negotiated, no longer pinned to the vessel's preferred stay), and it
     * KEPT the flat fee on purpose: the 19b spec's fee-per-hours idea was a sketch, its
     * acceptance criteria never touch income, and with the frozen clock (until task 24) a
     * shorter stay buys the player nothing — so re-pricing by hours would change §7.5
     * calibration for no gameplay gain. If a fee-per-hours model is ever wanted (task 24+,
     * when berth throughput becomes real), it belongs HERE, in this method's body, and
     * nowhere else. Everything downstream — the ledgers, the events, the EOD aggregation,
     * the §7.5 calibration — consumes only this method's return value, so that change stays
     * this body plus its table-driven test. Do not add an hours term to any other method.
     *
     * @param vesselType one of the 5 canonical types, or {@code null} when the caller does not
     *                   know it. Validated only when supplied — a typo must not price silently,
     *                   but neither live path can supply one: {@code DealClosedEvent} wraps the
     *                   canonical {@code Deal} (task-17's deliberate shape — dealId, vesselId,
     *                   berthId, finalPrice, finalHours, outcome; no type), and
     *                   {@code ContractedFeeEarnedEvent} mirrors it. The parameter is kept because
     *                   {@code planning/20} §20.1 specifies it and because 19b's fee-per-hours
     *                   model is likely to be type-dependent; the table-driven tests do pass it.
     * @param hours      the deal's stay length; ignored today, see above
     * @param dealPrice  the already-settled fee (€)
     */
    public static double berthBase(String vesselType, int hours, double dealPrice) {
        if (vesselType != null) {
            requireKnownType(vesselType);
        }
        if (dealPrice < 0) {
            throw new IllegalArgumentException("dealPrice must be >= 0: " + dealPrice);
        }
        return dealPrice;
    }

    /** +€500 for every tug beyond the first ({@code tugsUsed <= 1} earns nothing). */
    public static double extraTugSurcharge(int tugsUsed) {
        if (tugsUsed < 0) {
            throw new IllegalArgumentException("tugsUsed must be >= 0: " + tugsUsed);
        }
        return tugsUsed <= 1 ? 0.0 : (tugsUsed - 1) * EXTRA_TUG_EUR;
    }

    /** +€500 for a hazmat cargo. */
    public static double hazmatSurcharge(boolean isHazmat) {
        return isHazmat ? HAZMAT_EUR : 0.0;
    }

    /** Flat +€300 when a pilot is used. */
    public static double pilotService(boolean used) {
        return used ? PILOT_EUR : 0.0;
    }

    /** Negotiated bunker-fuel sale — a passthrough of whatever was agreed. */
    public static double bunkerFuel(double negotiated) {
        if (negotiated < 0) {
            throw new IllegalArgumentException("negotiated must be >= 0: " + negotiated);
        }
        return negotiated;
    }

    /** Flat +€150 water and waste service. */
    public static double waterAndWaste() {
        return WATER_WASTE_EUR;
    }

    /**
     * +15% of {@code baseTotal}, but only at reputation ≥ {@value #PREMIUM_REPUTATION_THRESHOLD}
     * — the reward for running a well-regarded port. Below the threshold: nothing.
     */
    public static double premiumSurcharge(double baseTotal, double reputation) {
        return reputation >= PREMIUM_REPUTATION_THRESHOLD ? baseTotal * PREMIUM_PCT : 0.0;
    }

    /**
     * Static fair-price band for {@code vesselType} (PROJECT_DEFINITION §7.5), used by the
     * Assistant when {@code MarketHistoryArtifact} sample count is too low to trust (&lt; 10).
     * {@code berthId} is accepted for a future per-berth premium but unused — every berth uses
     * the same type-keyed band, and task 20 kept it that way deliberately: §7.5 is the single
     * source of truth and it has no per-berth column to honour.
     */
    public static PriceRange berthFeeRange(String berthId, String vesselType) {
        return requireKnownType(vesselType);
    }

    /**
     * Read-only sum of every {@link IncomeEvent} the ledger recorded for game {@code day} —
     * consumed by {@code DayRolloverCoordinator} (task 24).
     *
     * <p>Does NOT charge or mutate anything: per-deal income was already applied to the wallet
     * during the day, as it happened. This only reads it back, which is precisely what keeps EOD
     * from double-charging. The day bucket is derived from each event's own {@code simTime}
     * ({@link SimClock#gameDayOf}), so it cannot disagree with the history's timestamps.
     */
    public static double aggregateForDay(WalletLedger ledger, int day) {
        return ledger.incomeHistory().stream()
                .filter(e -> SimClock.gameDayOf(e.simTime()) == day)
                .mapToDouble(IncomeEvent::amount)
                .sum();
    }

    private static PriceRange requireKnownType(String vesselType) {
        PriceRange range = FEE_RANGES.get(vesselType);
        if (range == null) {
            throw new IllegalArgumentException("unknown vessel type: " + vesselType);
        }
        return range;
    }
}
