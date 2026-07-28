package it.unige.portcommand.harbourmaster.financial;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.util.SimClock;

/**
 * Every way the port spends money (task 20 — owns this class; it began life as a constants-only
 * task-10 stub for {@code marginalCost}).
 *
 * <p>Deliberately Java, not Prolog (CLAUDE.md rule 4 / INVARIANTS — financial helpers stay out of
 * the 30-rule kernel). Pure arithmetic; {@link #variableForDay} is the one read-only exception.
 */
public final class ExpenseRules {

    /** Source tags for {@link ExpenseEvent#source()} — the ledger's audit vocabulary. */
    public static final String SOURCE_TUG_JOB = "tug_job";
    public static final String SOURCE_CUSTOMS = "customs_clearance";
    public static final String SOURCE_INCIDENT_FINE = "incident_fine";
    public static final String SOURCE_SALARIES = "salaries";
    public static final String SOURCE_BERTH_MAINTENANCE = "berth_maintenance";
    public static final String SOURCE_TUG_IDLE_LEASE = "tug_idle_lease";
    public static final String SOURCE_INSURANCE = "insurance";
    public static final String SOURCE_UTILITIES = "utilities";

    /**
     * The five fixed daily lines, in canonical order. This map — not a literal {@code 5200.0} —
     * is the single source of the daily fixed cost: {@link #dailyFixed()} SUMS it, so the total
     * cannot drift from its components, and the €5050 bug CLAUDE.md rule 7 forbids is
     * unrepresentable without visibly editing one of these five numbers.
     *
     * <p>Deliberate deviation from {@code planning/20} §20.2's "Configurable in defaults.json":
     * kept a hard-coded constant. CLAUDE.md rule 7 pins €5200 as an invariant and task 24 throws
     * if it ever reads anything else — making it configurable would add exactly the drift vector
     * that rule exists to forbid, for a number no scenario is allowed to vary.
     */
    private static final Map<String, Double> DAILY_FIXED = fixedBreakdown();

    /** Fixed lines are charged once per day by task 24 — {@link #variableForDay} must exclude them. */
    private static final Set<String> FIXED_SOURCES = DAILY_FIXED.keySet();

    /** planning/20: customs_clearance €100 per clearance. */
    private static final double CUSTOMS_CLEARANCE_EUR = 100.0;
    /** planning/20: incident_fine €2000. */
    private static final double INCIDENT_FINE_EUR = 2_000.0;

    // ---- marginalCost inputs (task-10's Assistant EV estimate; unchanged by task 20) ----

    // Typical tug count per type (PROJECT_DEFINITION §5.2's "expected per type over the
    // template ranges": tanker 2 / container 2 / cargo 1-2 / cruise 2 / ferry 0). The real
    // per-vessel count (PrologQueries.tugsRequired) also needs vessel length, which this
    // constants-only estimate does not receive.
    private static final Map<String, Integer> TYPICAL_TUGS = Map.of(
            "tanker", 2,
            "container_vessel", 2,
            "cargo_vessel", 1,
            "ferry", 0,
            "cruise_ship", 2);

    /** €/tug job — matches AgentRoster's tug baseFare and planning/20's tug_job (350-600) floor. */
    private static final double AVG_TUG_FEE = 350.0;
    private static final double CUSTOMS_SHARE = CUSTOMS_CLEARANCE_EUR;
    /** Amortised berth-occupancy overhead per vessel-hour. */
    private static final double BERTH_OCCUPANCY_PER_HOUR = 50.0;

    private ExpenseRules() {
    }

    /**
     * What a tug escort costs: the winning Contract Net bid, verbatim. A passthrough, because the
     * price was set by the tug's own PROPOSE ({@code BidInCNPBehaviour}:
     * {@code baseFare + fuelCostPerKm × distanceKm}) and chosen by Prolog {@code selectBestBids}
     * — re-deriving it here would silently decouple the ledger from the negotiation that produced
     * it, and a cheaper winning bid would stop saving the port money.
     */
    public static double tugJob(double winningBid) {
        if (winningBid < 0) {
            throw new IllegalArgumentException("winningBid must be >= 0: " + winningBid);
        }
        return winningBid;
    }

    /** €100 per customs clearance granted. */
    public static double customsClearance() {
        return CUSTOMS_CLEARANCE_EUR;
    }

    /** €2000 per safety incident. */
    public static double incidentFine() {
        return INCIDENT_FINE_EUR;
    }

    /**
     * The fixed daily cost: <strong>€5200</strong>, summed from {@link #dailyFixedBreakdown()}'s
     * five lines (150×4 salaries + 1200 berth maintenance + 400×4 tug idle lease + 1500 insurance
     * + 300 utilities). Never €5050 — CLAUDE.md rule 7. Task 24's {@code runEndOfDay} charges
     * these lines exactly once per day and asserts this total.
     */
    public static double dailyFixed() {
        return DAILY_FIXED.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /**
     * The five fixed daily lines by source tag, in canonical order — what task 24 charges
     * line-by-line so the wallet's history names each cost rather than one opaque €5200 lump.
     * {@code LinkedHashMap}-backed and unmodifiable per INVARIANTS' determinism rule.
     */
    public static Map<String, Double> dailyFixedBreakdown() {
        return DAILY_FIXED;
    }

    /**
     * Marginal cost of servicing one more vessel (tug escort + berth-occupancy share + customs-
     * handling share) — the Assistant's EV computation input (planning/10 §10.2 step 5).
     * {@code cargoClass} is accepted for a future hazmat surcharge but unused.
     */
    public static double marginalCost(String vesselType, String cargoClass, int hours, int tonnage) {
        Integer tugs = TYPICAL_TUGS.get(vesselType);
        if (tugs == null) {
            throw new IllegalArgumentException("unknown vessel type: " + vesselType);
        }
        return tugs * AVG_TUG_FEE + CUSTOMS_SHARE + hours * BERTH_OCCUPANCY_PER_HOUR;
    }

    /**
     * Read-only sum of the day's VARIABLE expenses — tug jobs, customs clearances, incident fines
     * — consumed by {@code DayRolloverCoordinator} (task 24).
     *
     * <p>Excludes the five fixed lines <strong>by source tag</strong>, not by timing. Task 24
     * charges the fixed €5200 into this same ledger, so those events land in the same day's
     * history; filtering on {@link #FIXED_SOURCES} means this stays correct even if EOD is
     * recomputed or replayed, rather than depending on being called before the fixed charge.
     * Does not charge or mutate: these costs were already applied when they happened.
     */
    public static double variableForDay(WalletLedger ledger, int day) {
        return ledger.expenseHistory().stream()
                .filter(e -> SimClock.gameDayOf(e.simTime()) == day)
                .filter(e -> !FIXED_SOURCES.contains(e.source()))
                .mapToDouble(ExpenseEvent::amount)
                .sum();
    }

    private static Map<String, Double> fixedBreakdown() {
        Map<String, Double> lines = new LinkedHashMap<>();
        lines.put(SOURCE_SALARIES, 150.0 * 4);          //  600 — 4 staff × €150
        lines.put(SOURCE_BERTH_MAINTENANCE, 1_200.0);   // 1200
        lines.put(SOURCE_TUG_IDLE_LEASE, 400.0 * 4);    // 1600 — 4 tugs × €400
        lines.put(SOURCE_INSURANCE, 1_500.0);           // 1500
        lines.put(SOURCE_UTILITIES, 300.0);             //  300
        return Collections.unmodifiableMap(lines);      // ---- 5200
    }
}
