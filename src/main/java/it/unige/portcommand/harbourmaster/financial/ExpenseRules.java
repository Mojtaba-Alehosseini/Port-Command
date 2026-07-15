package it.unige.portcommand.harbourmaster.financial;

import java.util.Map;

/**
 * Minimal constants-only stub (v1.1) — created here because task 10 (Phase 2) needs a marginal-
 * cost estimate for its EV computation before task 20 (Phase 5) exists; task 20 refines and owns
 * final calibration (dailyFixed, incidentFine, variableForDay, PerformativeCounter, etc. — see
 * {@code planning/20_financial_dashboard.md}). Deliberately Java, not Prolog (CLAUDE.md rule 4 /
 * INVARIANTS.md — financial helpers stay out of the 30-rule kernel).
 */
public final class ExpenseRules {

    // Typical tug count per type (PROJECT_DEFINITION §5.2's "expected per type over the
    // template ranges": tanker 2 / container 2 / cargo 1-2 / cruise 2 / ferry 0). The real
    // per-vessel count (PrologQueries.tugsRequired) also needs vessel length, which this
    // constants-only stub does not receive — task 20 may wire the Prolog-derived count instead.
    private static final Map<String, Integer> TYPICAL_TUGS = Map.of(
            "tanker", 2,
            "container_vessel", 2,
            "cargo_vessel", 1,
            "ferry", 0,
            "cruise_ship", 2);

    // €/tug job — matches AgentRoster's tug baseFare and planning/20's tug_job (350-600) floor.
    private static final double AVG_TUG_FEE = 350.0;
    // Matches planning/20's ExpenseRules.customsClearance() constant (task 20 owns that method;
    // this stub inlines the same figure for the marginal-cost estimate).
    private static final double CUSTOMS_SHARE = 100.0;
    // Amortised berth-occupancy overhead per vessel-hour; a placeholder pending task 20's real
    // berth_maintenance/day allocation across a day's vessel traffic.
    private static final double BERTH_OCCUPANCY_PER_HOUR = 50.0;

    private ExpenseRules() {
    }

    /**
     * Marginal cost of servicing one more vessel (tug escort + berth-occupancy share + customs-
     * handling share) — the Assistant's EV computation input (planning/10 §10.2 step 5).
     * {@code cargoClass} is accepted for a future hazmat surcharge (task 20) but unused by this
     * stub.
     */
    public static double marginalCost(String vesselType, String cargoClass, int hours, int tonnage) {
        Integer tugs = TYPICAL_TUGS.get(vesselType);
        if (tugs == null) {
            throw new IllegalArgumentException("unknown vessel type: " + vesselType);
        }
        return tugs * AVG_TUG_FEE + CUSTOMS_SHARE + hours * BERTH_OCCUPANCY_PER_HOUR;
    }
}
