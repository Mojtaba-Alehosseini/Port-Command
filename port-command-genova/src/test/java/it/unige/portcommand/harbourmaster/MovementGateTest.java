package it.unige.portcommand.harbourmaster;

import java.util.List;

import it.unige.portcommand.harbourmaster.MovementGate.Reading;
import it.unige.portcommand.harbourmaster.MovementGate.Verdict;
import it.unige.portcommand.prolog.PrologEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one weather check behind every movement (S16, ADR-20, decisions B2 and B4), against the
 * real kernel: which rules refuse which reading for which vessel type, the three-knot release
 * margin, and "no reading is not safe".
 *
 * <p>The literals are the kernel's own ({@code rules_weather.pl}): wind limits tanker 30,
 * ferry and cruise ship 35, container vessel 40, cargo vessel 45 — strict, so the limit itself
 * is already unsafe; visibility good or fair; swell up to and including 4.0 m; sea state not
 * stormy. Written out here on purpose: a test that computed them from the kernel would prove the
 * wiring and not the policy.
 */
class MovementGateTest {

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    private static Reading reading(double wind, String visibility, double swell, String state) {
        return new Reading(wind, visibility, swell, state);
    }

    // ---- check: may a movement start or continue? ------------------------------------------------

    @Test
    void aCalmReadingIsSafeForEveryTypeAndNamesNoRule() {
        for (String type : List.of("tanker", "ferry", "cruise_ship", "container_vessel", "cargo_vessel")) {
            Verdict verdict = MovementGate.check(type, reading(18, "good", 0.5, "sunny"));
            assertTrue(verdict.safe(), type);
            assertEquals(List.of(), verdict.ruleIds(), type);
            assertEquals("", verdict.reason(), type);
            assertEquals("", verdict.ruleTag(), type);
        }
    }

    @Test
    void theWindLimitIsPerTypeAndStrict() {
        assertTrue(MovementGate.check("tanker", reading(29, "fair", 1.5, "cloudy")).safe());
        assertEquals(List.of("R15"), MovementGate.check("tanker", reading(30, "fair", 1.5, "cloudy")).ruleIds(),
                "30 kn IS the tanker limit, and the limit is already unsafe");
        assertTrue(MovementGate.check("ferry", reading(34, "fair", 1.5, "cloudy")).safe());
        assertEquals(List.of("R15"), MovementGate.check("ferry", reading(35, "fair", 1.5, "cloudy")).ruleIds());
        assertTrue(MovementGate.check("cruise_ship", reading(34, "fair", 1.5, "cloudy")).safe());
        assertTrue(MovementGate.check("container_vessel", reading(39, "fair", 1.5, "cloudy")).safe());
        assertFalse(MovementGate.check("container_vessel", reading(40, "fair", 1.5, "cloudy")).safe());
        assertTrue(MovementGate.check("cargo_vessel", reading(44, "fair", 1.5, "cloudy")).safe());
        assertFalse(MovementGate.check("cargo_vessel", reading(45, "fair", 1.5, "cloudy")).safe());
    }

    @Test
    void eachRuleRefusesOnItsOwnAndIsNamedOnItsOwn() {
        assertEquals(List.of("R16"), MovementGate.check("cargo_vessel", reading(20, "poor", 1.5, "cloudy")).ruleIds(),
                "poor visibility alone (S16 lets the weather produce it without a storm)");
        assertEquals(List.of("R17"), MovementGate.check("cargo_vessel", reading(20, "good", 4.1, "cloudy")).ruleIds(),
                "swell over 4.0 m alone");
        assertTrue(MovementGate.check("cargo_vessel", reading(20, "good", 4.0, "cloudy")).safe(),
                "4.0 m is inside the limit");
        assertEquals(List.of("R19"), MovementGate.check("cargo_vessel", reading(20, "good", 1.0, "stormy")).ruleIds(),
                "a stormy sea state alone");
    }

    @Test
    void theStormScenariosReadingRefusesATankerOnThreeRulesAndACargoVesselOnTwo() {
        Reading storm = reading(32, "poor", 4.0, "stormy");
        Verdict tanker = MovementGate.check("tanker", storm);
        assertFalse(tanker.safe());
        assertEquals(List.of("R15", "R16", "R19"), tanker.ruleIds(), "in rule order; 4.0 m passes R17");
        assertEquals("[R15,R16,R19]", tanker.ruleTag());
        assertEquals("wind 32 kn, visibility poor, sea state stormy [R15,R16,R19]", tanker.reason());

        Verdict cargo = MovementGate.check("cargo_vessel", storm);
        assertEquals(List.of("R16", "R19"), cargo.ruleIds(), "32 kn is inside a cargo vessel's 45 kn limit");
        assertEquals("visibility poor, sea state stormy [R16,R19]", cargo.reason());
    }

    @Test
    void noReadingIsClosedAndNamesNoRule() {
        Verdict verdict = MovementGate.check("cargo_vessel", null);
        assertFalse(verdict.safe(), "B7: 'nobody has told us yet' is not 'safe'");
        assertEquals(List.of(), verdict.ruleIds());
        assertEquals("no weather reading yet", verdict.reason());
        assertFalse(MovementGate.release("cargo_vessel", null).safe());
        assertFalse(MovementGate.calmForEveryType(null));
    }

    // ---- release: may a HELD ship be let go? (B4, hysteresis) -----------------------------------

    @Test
    void aHeldTankerIsNotReleasedAtOneOrTwoKnotsUnderItsLimit_onlyAtThree() {
        // Born red: before S16 the release test WAS the hold test, so 29 kn released a tanker
        // that 30 kn would hold again — cloudy wind wanders 15..32, an escort hired and
        // cancelled on alternate hours.
        Verdict at29 = MovementGate.release("tanker", reading(29, "fair", 1.5, "cloudy"));
        assertFalse(at29.safe(), "limit - 1: under the limit, but not by the margin");
        assertEquals(List.of("R15"), at29.ruleIds());
        assertEquals("wind 29 kn, resumes at 27 kn or less [R15]", at29.reason());
        assertFalse(MovementGate.release("tanker", reading(28, "fair", 1.5, "cloudy")).safe(), "limit - 2");
        assertTrue(MovementGate.release("tanker", reading(27, "fair", 1.5, "cloudy")).safe(), "limit - 3");
        assertTrue(MovementGate.check("tanker", reading(29, "fair", 1.5, "cloudy")).safe(),
                "the margin is for RELEASING; a tanker that was never held may still move at 29");
    }

    @Test
    void theMarginIsMeasuredFromEachTypesOwnLimit() {
        assertFalse(MovementGate.release("ferry", reading(33, "fair", 1.5, "cloudy")).safe());
        assertTrue(MovementGate.release("ferry", reading(32, "fair", 1.5, "cloudy")).safe());
        assertFalse(MovementGate.release("container_vessel", reading(38, "fair", 1.5, "cloudy")).safe());
        assertTrue(MovementGate.release("container_vessel", reading(37, "fair", 1.5, "cloudy")).safe());
        assertFalse(MovementGate.release("cargo_vessel", reading(43, "fair", 1.5, "cloudy")).safe());
        assertTrue(MovementGate.release("cargo_vessel", reading(42, "fair", 1.5, "cloudy")).safe());
    }

    @Test
    void aReleaseStillNeedsEveryOtherConditionSafe() {
        assertEquals(List.of("R16"), MovementGate.release("tanker", reading(20, "poor", 1.5, "cloudy")).ruleIds());
        assertEquals(List.of("R17"), MovementGate.release("tanker", reading(20, "good", 4.5, "cloudy")).ruleIds());
        assertEquals(List.of("R19"), MovementGate.release("tanker", reading(20, "good", 1.0, "stormy")).ruleIds());
        assertEquals(List.of("R15", "R19"), MovementGate.release("tanker", reading(31, "good", 1.0, "stormy")).ruleIds(),
                "over the limit is reported as over the limit, not as 'not yet by the margin'");
        assertEquals("wind 31 kn, sea state stormy [R15,R19]",
                MovementGate.release("tanker", reading(31, "good", 1.0, "stormy")).reason());
    }

    // ---- calm for every type: what "clearing" means ---------------------------------------------

    @Test
    void calmMeansEveryTypeCouldBeReleased() {
        assertTrue(MovementGate.calmForEveryType(reading(27, "fair", 1.5, "cloudy")),
                "27 kn releases even a tanker");
        assertFalse(MovementGate.calmForEveryType(reading(28, "fair", 1.5, "cloudy")),
                "28 kn would still hold a held tanker");
        assertFalse(MovementGate.calmForEveryType(reading(20, "poor", 1.5, "cloudy")));
        assertFalse(MovementGate.calmForEveryType(reading(20, "good", 4.1, "cloudy")));
        assertFalse(MovementGate.calmForEveryType(reading(20, "good", 1.0, "stormy")));
        assertTrue(MovementGate.calmForEveryType(reading(24, "good", 1.0, "cloudy")),
                "the storm scenario's scripted clear");
    }

    @Test
    void anUnknownTypeIsNeverSafe() {
        assertFalse(MovementGate.check("pleasure", reading(5, "good", 0.3, "sunny")).safe(),
                "R15 has no limit for a type the ontology does not know, so it cannot pass");
    }
}
