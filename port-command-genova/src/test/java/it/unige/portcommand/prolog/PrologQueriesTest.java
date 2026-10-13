package it.unige.portcommand.prolog;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Method-by-method coverage of {@link PrologQueries} against the known ontology
 * fixture, mirroring the PLUnit suites but through the Java/JPL surface. Proves
 * the transient facts are asserted in the {@code user:} module the rules read,
 * the documented bid tiebreaker, the blacklist round-trip, and pure-query caching.
 */
class PrologQueriesTest {

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    // --- Compatibility -----------------------------------------------------

    @Test
    void tankerFitsBerth1ButNotBerth4OnDraft() {
        // Length 140 and tanker beam both clear berth_1 and berth_4; only draft differs.
        assertTrue(PrologQueries.isCompatible("berth_1", "tanker", 14.0, 140.0, 20000),
                "tanker (draft 14) fits berth_1 (max draft 22)");
        assertFalse(PrologQueries.isCompatible("berth_4", "tanker", 14.0, 140.0, 20000),
                "tanker (draft 14) exceeds berth_4 max draft 9");
    }

    @Test
    void findCompatibleBerthsIncludesBerth1ExcludesBerth4() {
        List<String> berths = PrologQueries.findCompatibleBerths("tanker", 14.0, 140.0, 20000);
        assertTrue(berths.contains("berth_1"), "tanker fits deep-water berth_1; got " + berths);
        assertFalse(berths.contains("berth_4"), "tanker must not fit ferry berth_4; got " + berths);
        assertFalse(berths.contains("berth_3"), "berth_3 max draft 12 < 14; got " + berths);
    }

    // --- Escort ------------------------------------------------------------

    // Canonical tug outcomes per PROJECT_DEFINITION S5.2 (updated by task 07b, v1.1
    // 2026-07-04): ferry exempt (own propulsion); every other type capped at 2 because
    // the fleet is only 4 tugs — the pre-07b kernel gave big tankers/cargo 3. See
    // MASTER_PLAN S8 items 20-21; the full type x tonnage matrix is locked in test_escort.pl.
    @ParameterizedTest(name = "tugsRequired({0}, {1}) = {2}")
    @CsvSource({
            "ferry,           15000, 0",   // ferry exempt — own propulsion (07b)
            "tanker,          15000, 2",   // base 1 (small) lifted to tanker minimum 2
            "tanker,         120000, 2",   // base 3 (large) capped at 2 — fleet cap (07b)
            "cargo_vessel,    60000, 2",   // base 3 (large) capped at 2 (07b)
            "container_vessel,30000, 2",   // mid bracket
    })
    void tugsRequiredByTypeAndTonnage(String vesselType, int tonnage, int expected) {
        assertEquals(expected, PrologQueries.tugsRequired(vesselType, tonnage, 140.0));
    }

    /**
     * S11 (logic hunt, L1): the drift pin between R11 and {@code ExpenseRules}' constants-only
     * EV estimate. {@code TYPICAL_TUGS} is private, but {@code marginalCost} exposes it
     * arithmetically — at hours=0, (marginalCost − 100 customs share) / 350 is the tug count
     * the Assistant prices. Every type is checked at the min, mid and max of its
     * {@code vessel_templates.json} tonnage range — the population the game can actually
     * spawn — read from the REAL loader so there is no third copy of the ranges. Cargo's
     * stale 1 (correct only below 20k tonnage, which no template produces) skewed every
     * cargo EV €350 cheap and survived precisely because nothing coupled the two sides; if a
     * future template/R9 edit makes a type's count non-uniform over its range, this fails
     * and forces the pricing decision by hand.
     */
    @Test
    void expenseRulesTypicalTugsMatchR11OverTheTemplateTonnageRanges() {
        for (String type : it.unige.portcommand.negotiation.VesselTemplates.types()) {
            int[] range = it.unige.portcommand.negotiation.VesselTemplates.forType(type).tonnageRange();
            int priced = (int) Math.round(
                    (it.unige.portcommand.harbourmaster.financial.ExpenseRules
                            .marginalCost(type, "general_cargo", 0, range[0]) - 100.0) / 350.0);
            for (int tonnage : new int[] {range[0], (range[0] + range[1]) / 2, range[1]}) {
                assertEquals(PrologQueries.tugsRequired(type, tonnage, 140.0), priced,
                        type + " at tonnage " + tonnage + ": the Assistant's EV prices " + priced
                                + " tug(s) but R11 requires a different count");
            }
        }
    }

    @Test
    void selectBestBidsHonoursScoreAndCount() {
        List<TugBid> bids = List.of(
                new TugBid("tug_a", 500, 6.0, 0.8),
                new TugBid("tug_b", 400, 5.0, 0.9),
                new TugBid("tug_c", 900, 12.0, 0.2));
        assertEquals(List.of("tug_b"), PrologQueries.selectBestBids(bids, 1), "n=1 -> top scorer");
        assertEquals(List.of("tug_b", "tug_a"), PrologQueries.selectBestBids(bids, 2),
                "n=2 -> sorted descending by score");
        assertEquals(List.of(), PrologQueries.selectBestBids(List.of(), 2), "empty bids -> empty winners");
    }

    // --- Weather -----------------------------------------------------------

    @Test
    void operationSafeChecksWindAndVisibility() {
        assertTrue(PrologQueries.operationSafe("tanker", 28.0, "good"), "28kn < tanker limit 30, good vis");
        assertFalse(PrologQueries.operationSafe("tanker", 32.0, "good"), "32kn >= tanker limit 30");
        assertFalse(PrologQueries.operationSafe("tanker", 28.0, "poor"), "poor visibility unsafe");
    }

    // --- Customs -----------------------------------------------------------

    @Test
    void clearanceOkValidatesBothAtoms() {
        assertTrue(PrologQueries.clearanceOk("tanker", "hazmat_class_3"), "valid, not blacklisted");
        assertFalse(PrologQueries.clearanceOk("submarine", "general_cargo"), "submarine is not a vessel_type");
    }

    @Test
    void clearanceOkFlipsWithBlacklistRoundTrip() {
        PrologEngine e = PrologEngine.getInstance();
        // Asserting the blacklist via the engine proves the user: module is shared
        // with the rule (acceptance criterion: assert -> fail, retract -> succeed).
        e.assertFact("blacklisted_combo(tanker, hazmat_class_3)");
        try {
            assertFalse(PrologQueries.clearanceOk("tanker", "hazmat_class_3"),
                    "blacklisted pair must fail clearance");
        } finally {
            e.retractAllMatching("blacklisted_combo(tanker, hazmat_class_3)");
        }
        assertTrue(PrologQueries.clearanceOk("tanker", "hazmat_class_3"),
                "clearance restored after the blacklist entry is retracted");
    }

    @ParameterizedTest(name = "inspectionProbability({0}, {1}) = {2}")
    @CsvSource({
            "tanker,       hazmat_class_3, 0.8",
            "cargo_vessel, general_cargo,  0.2",
    })
    void inspectionProbabilityByHazmat(String vesselType, String cargoClass, double expected) {
        assertEquals(expected, PrologQueries.inspectionProbability(vesselType, cargoClass), 1e-9);
    }

    @Test
    void isHazmatClassifiesCargo() {
        assertTrue(PrologQueries.isHazmat("hazmat_class_3"), "hazmat_class_3 subclass_of hazmat_cargo");
        assertFalse(PrologQueries.isHazmat("general_cargo"), "general_cargo is not hazmat");
    }

    // --- Priority ----------------------------------------------------------

    // --- S5 facade additions (R18, R22, R24, R26-R30) ----------------------

    @ParameterizedTest(name = "windThresholdsCrossed({0}) = [{1}]")
    @CsvSource({
            "28,   ''",
            "30,   '30'",
            "36,   '30;35'",
            "41,   '30;35;40'",
            "50.0, '30;35;40;45'"})
    void windThresholdsCrossedEnumeratesAscending(double wind, String expectedJoined) {
        List<Integer> expected = expectedJoined.isEmpty() ? List.of()
                : java.util.Arrays.stream(expectedJoined.split(";")).map(Integer::parseInt).toList();
        assertEquals(expected, PrologQueries.windThresholdsCrossed(wind),
                "R18 threshold_crossed must enumerate every crossed band, ascending");
    }

    @Test
    void needsInspectionCoversTheWholeHazmatSubtree() {
        // R22 over the reflexive-transitive is_hazmat (S3): the PARENT class passes too.
        assertTrue(PrologQueries.needsInspection("hazmat_cargo"), "R22: the parent class itself");
        assertTrue(PrologQueries.needsInspection("hazmat_class_3"), "R22: a leaf");
        assertFalse(PrologQueries.needsInspection("general_cargo"), "R22 control");
        assertFalse(PrologQueries.needsInspection("not_a_cargo_class"),
                "R22 guards on cargo_class/1 — an unknown atom must fail, not throw");
    }

    @Test
    void highRiskTracksInspectionProbabilityAtEvenOdds() {
        // R24 = R23 >= 0.5: hazmat 0.8 -> true, otherwise 0.2 -> false.
        assertTrue(PrologQueries.highRisk("tanker", "hazmat_class_3"));
        assertFalse(PrologQueries.highRisk("cargo_vessel", "general_cargo"));
    }

    @Test
    void effectivePriorityBridgesEveryTypeThroughR26() {
        // R26 clause 2: only ferries get scheduled_ferry (rank 3); everything else rank 4.
        PrologQueries.VesselPriority ferry = PrologQueries.effectivePriority("ferry", null);
        assertEquals("scheduled_ferry", ferry.priorityClass(), "R26 ferry clause");
        assertEquals(3, ferry.rank());
        for (String type : List.of("tanker", "container_vessel", "cargo_vessel", "cruise_ship")) {
            PrologQueries.VesselPriority p = PrologQueries.effectivePriority(type, null);
            assertEquals("scheduled_arrival", p.priorityClass(), type);
            assertEquals(4, p.rank(), type);
        }
    }

    @Test
    void anOverrideOutranksTheTypeClassAndLeavesNoResidue() {
        PrologQueries.VesselPriority emergency = PrologQueries.effectivePriority("ferry", "emergency");
        assertEquals("emergency", emergency.priorityClass(), "R26 clause 1: the override wins");
        assertEquals(1, emergency.rank());
        // Residue check: the override was transient — a follow-up query with no override must
        // see the plain ferry class again (a leaked vessel_priority_override would break this).
        assertEquals("scheduled_ferry",
                PrologQueries.effectivePriority("ferry", null).priorityClass(),
                "the transient override fact must not survive its own query");
    }

    @Test
    void effectivePriorityFailsLoudlyOnAnUnknownType() {
        // R26 has NO fallback clause — a silent default would mask a wiring bug.
        assertThrows(PrologException.class,
                () -> PrologQueries.effectivePriority("submarine", null));
    }

    @Test
    void pairwisePriorityPredicatesAgreeWithTheRankTable() {
        assertTrue(PrologQueries.higherPriority("emergency", "maintenance"), "R27");
        assertFalse(PrologQueries.higherPriority("maintenance", "emergency"), "R27 asymmetry");
        assertTrue(PrologQueries.preempts("scheduled_ferry", "scheduled_arrival"), "R28");
        assertFalse(PrologQueries.preempts("scheduled_arrival", "scheduled_ferry"), "R28 asymmetry");
        assertTrue(PrologQueries.emergencyOverridesAll("scheduled_ferry"), "R29");
        assertFalse(PrologQueries.emergencyOverridesAll("emergency"),
                "R29 guard: emergency does not override itself");
    }

    @ParameterizedTest(name = "priorityRank({0}) = {1}")
    @CsvSource({
            "emergency,         1",
            "medical,           2",
            "scheduled_ferry,   3",
            "scheduled_arrival, 4",
            "walk_in_arrival,   5",
            "departure,         5",
            "maintenance,       6",
    })
    void priorityRankByClass(String classAtom, int expected) {
        assertEquals(expected, PrologQueries.priorityRank(classAtom));
    }

    @Test
    void unknownPriorityClassThrows() {
        assertThrows(PrologException.class, () -> PrologQueries.priorityRank("nonexistent_class"));
    }

    // --- Caching (pure queries only) ---------------------------------------

    @Test
    void pureQuerySecondCallHitsCache() {
        PrologEngine e = PrologEngine.getInstance();
        e.clearCache();
        long before = e.queryCount();
        int first = PrologQueries.priorityRank("emergency");
        long afterFirst = e.queryCount();
        int second = PrologQueries.priorityRank("emergency");
        long afterSecond = e.queryCount();

        assertEquals(1, first);
        assertEquals(1, second);
        assertEquals(before + 1, afterFirst, "first call dispatches exactly one JPL goal");
        assertEquals(afterFirst, afterSecond, "second call is served from cache (no new JPL goal)");
    }
}
