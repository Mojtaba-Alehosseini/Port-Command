package it.unige.portcommand.harbourmaster.financial;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PROJECT_DEFINITION §7.5 calibration acceptance: <em>"task 20 calibrates the default day to a
 * net margin of <strong>+€8,000–€12,000</strong> using the §5.2 tug counts"</em>.
 *
 * <p>A pure arithmetic simulation of a default day — no agents, no clock, no Prolog. It drives the
 * REAL {@link IncomeRules} / {@link ExpenseRules} methods rather than restating their numbers, so
 * it cannot pass by agreeing with itself: change a §7.5 band, the €5200 fixed cost, or the
 * extra-tug surcharge, and this CLASS fails.
 *
 * <p><strong>Which test actually guards what</strong> (the adversarial review measured this, so do
 * not conflate them): the real regression guard is
 * {@link #defaultDayArithmeticIsTheRecordedFigures}, which pins every figure exactly. The band test
 * is the §7.5 ACCEPTANCE, and it is deliberately loose — a 10,425 landing tolerates +1,575/−2,425,
 * so a small band edit (the review moved the tanker ceiling 8,000→8,500) shifts income without
 * failing it. That is the acceptance working as intended, not a hole; the exact-figures test is
 * what catches the drift. Do not delete the exact pin and rely on the band alone.
 *
 * <h2>What is canonical here, and what is a knob</h2>
 * <ul>
 *   <li><b>Canonical, never tuned:</b> the §7.5 fee bands ({@link IncomeRules#berthFeeRange}), the
 *       §5.2 tug counts, the €5200 fixed daily cost, the €500 extra-tug surcharge.</li>
 *   <li><b>Knobs, tuned to land in the band:</b> the arrival mix and the assumed winning tug bid.
 *       Both are game-design choices with no canonical source.</li>
 * </ul>
 * The mix below is modelled on the demo transcript's Day 1 (2 contracted + 4 walk-ins, one of
 * which withdraws) and the real {@code contracts.json}. It lands at <b>+€10,425</b>, near
 * mid-band — see the task-20 Done note in {@code planning/20_financial_dashboard.md}.
 */
class DefaultDayCalibrationTest {

    private static final double EPS = 1e-9;

    /** §7.5 acceptance band for a default day's net margin. */
    private static final double BAND_LO = 8_000.0;
    private static final double BAND_HI = 12_000.0;

    /**
     * One arriving vessel. {@code negotiatedFee} is {@code null} for a walk-in (priced at its §7.5
     * band midpoint) and set for a contracted vessel (priced by {@code contracts.json}).
     */
    private record Arrival(String vesselType, int tugs, Double contractedFee, boolean deals) {

        static Arrival contracted(String type, int tugs, double fee) {
            return new Arrival(type, tugs, fee, true);
        }

        static Arrival walkIn(String type, int tugs) {
            return new Arrival(type, tugs, null, true);
        }

        static Arrival withdraws(String type) {
            return new Arrival(type, 0, null, false);
        }
    }

    /**
     * The default day. Tug counts are PROJECT_DEFINITION §5.2's canonical outcomes — tanker 2,
     * container 2, cargo 1, cruise 2, ferry 0 (own propulsion) — the same table
     * {@code ExpenseRulesTest.marginalCostUsesTheCanonicalTugCountPerType} pins through the
     * marginal-cost formula. Contracted fees/types are the real {@code contracts.json}: C001 a
     * tanker at berth_1 for €5200, F001 a ferry at berth_4 for €1800.
     *
     * <p>A withdrawing walk-in earns nothing and consumes nothing — it withdraws during
     * negotiation, before any tug is dispatched. It is in the mix because a day where every vessel
     * deals is not a default day.
     */
    private static final List<Arrival> DEFAULT_DAY = List.of(
            Arrival.contracted("tanker", 2, 5_200.0),
            Arrival.contracted("ferry", 0, 1_800.0),
            Arrival.walkIn("cargo_vessel", 1),
            Arrival.walkIn("container_vessel", 2),
            Arrival.walkIn("tanker", 2),
            Arrival.withdraws("cargo_vessel"));

    /**
     * Knob: the assumed winning Contract Net bid, mid of planning/20's €350–600 {@code tug_job}
     * band. The real bid is {@code baseFare(350) + fuelCostPerKm × distanceKm}, so it varies with
     * where the tug happens to be.
     */
    private static final double ASSUMED_TUG_BID = 475.0;

    @Test
    void defaultDayNetMarginLandsInTheSevenPointFiveBand() {
        double income = dayIncome();
        double variable = dayVariableExpense();
        double fixed = ExpenseRules.dailyFixed();
        double net = income - variable - fixed;

        assertTrue(net >= BAND_LO && net <= BAND_HI,
                String.format("default day net EUR%.0f is outside the §7.5 band EUR%.0f–EUR%.0f"
                                + " (income EUR%.0f - variable EUR%.0f - fixed EUR%.0f)",
                        net, BAND_LO, BAND_HI, income, variable, fixed));
    }

    /**
     * The recorded landing, pinned exactly. The band test above is the acceptance; this one is the
     * regression that tells you WHICH number moved when the band test fails, and keeps the figure
     * in the planning Done note honest.
     */
    @Test
    void defaultDayArithmeticIsTheRecordedFigures() {
        // Income: contracted 5200 + 1800 = 7000; walk-in §7.5 midpoints 1800 + 2650 + 6000 = 10450;
        // extra-tug surcharges 500 each on the 2-tug tanker (contracted), container and tanker
        // walk-ins = 1500. The CONTRACTED tanker's own surcharge is easy to forget — it is why this
        // is 18950 and not 18450, which is exactly what this test caught.
        assertEquals(18_950.0, dayIncome(), EPS, "7000 contracted + 10450 walk-in mids + 1500 extra-tug");
        assertEquals(3_325.0, dayVariableExpense(), EPS, "7 tug jobs x EUR475");
        assertEquals(5_200.0, ExpenseRules.dailyFixed(), EPS);
        assertEquals(10_425.0, dayIncome() - dayVariableExpense() - ExpenseRules.dailyFixed(), EPS);
    }

    /**
     * Non-vacuity guard: the band must not be so wide that the mix is irrelevant. A day of nothing
     * but the fixed cost must FAIL the band — otherwise the acceptance above proves nothing.
     */
    @Test
    void anEmptyDayWouldFailTheBand() {
        double net = 0.0 - 0.0 - ExpenseRules.dailyFixed();

        assertTrue(net < BAND_LO, "an empty day nets -EUR5200 and must not satisfy the band");
    }

    /** Reputation starts at 50, so no premium is in the calibration — it unlocks at 80. */
    @Test
    void theDefaultDayEarnsNoPremiumBecauseReputationStartsAtFifty() {
        assertEquals(0.0, IncomeRules.premiumSurcharge(dayIncome(), 50.0), EPS);
    }

    private static double dayIncome() {
        double total = 0.0;
        for (Arrival arrival : DEFAULT_DAY) {
            if (!arrival.deals()) {
                continue; // a withdrawal earns nothing
            }
            double fee = arrival.contractedFee() != null
                    ? arrival.contractedFee()
                    : IncomeRules.berthFeeRange("berth_1", arrival.vesselType()).midpoint();
            total += IncomeRules.berthBase(arrival.vesselType(), 8, fee);
            total += IncomeRules.extraTugSurcharge(arrival.tugs());
        }
        return total;
    }

    private static double dayVariableExpense() {
        double total = 0.0;
        for (Arrival arrival : DEFAULT_DAY) {
            total += arrival.tugs() * ExpenseRules.tugJob(ASSUMED_TUG_BID);
        }
        return total; // no hazmat in the default mix, so no customs clearance
    }
}
