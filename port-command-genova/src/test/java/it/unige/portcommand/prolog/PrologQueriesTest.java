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

    @ParameterizedTest(name = "tugsRequired({0}, {1}) = {2}")
    @CsvSource({
            "tanker,          15000, 2",   // base 1 (small) lifted to tanker minimum 2
            "cargo_vessel,    60000, 3",   // large bracket
            "ferry,           15000, 1",   // small bracket, no type minimum
            "container_vessel,30000, 2",   // mid bracket
    })
    void tugsRequiredByTypeAndTonnage(String vesselType, int tonnage, int expected) {
        assertEquals(expected, PrologQueries.tugsRequired(vesselType, tonnage, 140.0));
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

    @ParameterizedTest(name = "priorityRank({0}) = {1}")
    @CsvSource({
            "emergency,         1",
            "medical,           2",
            "scheduled_ferry,   3",
            "scheduled_arrival, 4",
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
