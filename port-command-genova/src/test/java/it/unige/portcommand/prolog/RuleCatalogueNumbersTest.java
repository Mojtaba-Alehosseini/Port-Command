package it.unige.portcommand.prolog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.unige.portcommand.core.RuleCatalogue;
import org.jpl7.Term;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S18b (UI tree U17): "Rule catalogue test pins ids/predicates but not the numbers in the texts
 * (30/35/40/45 kn, 4.0 m, 80%/20%)." The figures the comm log's rule line shows the player are
 * asked of the kernel as SWI-Prolog loaded it — a limit changed in a {@code .pl} file and not in
 * {@code rule_catalogue.json} fails here. Same lane and {@code init()} idiom as
 * {@link RuleCatalogueKernelTest}.
 */
class RuleCatalogueNumbersTest {

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    private static String text(String ruleId) {
        return RuleCatalogue.get().all().stream().filter(r -> r.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + ruleId + " in the catalogue")).text();
    }

    private static Term one(String goal, String variable) {
        Map<String, Term> solution = PrologEngine.getInstance().oneSolution(goal)
                .orElseThrow(() -> new AssertionError("the kernel has no answer to " + goal));
        return solution.get(variable);
    }

    private static long windLimit(String vesselType) {
        return one("wind_limit(" + vesselType + ", L)", "L").longValue();
    }

    /** R15: one limit per vessel type; the text gives ferry and cruise one figure. */
    @Test
    void theWindLimitsInR15AreTheKernels() {
        String r15 = text("R15");

        assertTrue(r15.contains("tanker " + windLimit("tanker") + ","), r15);
        assertEquals(windLimit("ferry"), windLimit("cruise_ship"), "the text names ferry and cruise together");
        assertTrue(r15.contains("ferry and cruise " + windLimit("ferry") + ","), r15);
        assertTrue(r15.contains("container " + windLimit("container_vessel") + ","), r15);
        assertTrue(r15.contains("cargo " + windLimit("cargo_vessel") + " knots"), r15);
    }

    /** R17: the swell limit, with its one decimal. */
    @Test
    void theSwellLimitInR17IsTheKernels() {
        double maxSwell = one("max_swell(M)", "M").doubleValue();

        assertTrue(text("R17").contains(String.format(Locale.ROOT, "%.1f metre", maxSwell)), text("R17"));
    }

    /** R18: the thresholds a strong wind crosses, in order. */
    @Test
    void theThresholdsInR18AreTheKernels() {
        List<Long> thresholds = PrologEngine.getInstance().allSolutions("threshold_crossed(1000, T)").stream()
                .map(solution -> solution.get("T").longValue()).toList();
        assertEquals(4, thresholds.size(), "setup: " + thresholds);

        String expected = "(" + thresholds.get(0) + ", " + thresholds.get(1) + ", " + thresholds.get(2) + " and "
                + thresholds.get(3) + " knots)";
        assertTrue(text("R18").contains(expected), text("R18") + " should say " + expected);
    }

    /** R23: the inspection chance for hazardous and for any other cargo. */
    @Test
    void theInspectionChancesInR23AreTheKernels() {
        long hazmat = Math.round(one("inspection_probability(tanker, hazmat_cargo, P)", "P").doubleValue() * 100);
        long other = Math.round(one("inspection_probability(tanker, general_cargo, P)", "P").doubleValue() * 100);

        String r23 = text("R23");
        assertTrue(r23.contains(hazmat + "% for hazardous cargo"), r23);
        assertTrue(r23.contains(other + "% for any other cargo"), r23);
    }

    /** R24: "high-risk when its inspection chance is 50% or more" — the bound sits in a rule body,
     * not a fact, so it is read off the rule file, and the kernel is asked both sides of it. */
    @Test
    void theHighRiskBoundInR24IsTheKernels() throws IOException {
        String source;
        try (InputStream in = RuleCatalogueNumbersTest.class.getResourceAsStream("/prolog/rules_customs.pl")) {
            assertTrue(in != null, "the customs rules are on the classpath");
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(source.contains("Prob >= 0.5"), "R24's bound in the kernel");
        assertTrue(text("R24").contains("50% or more"), text("R24"));

        PrologEngine engine = PrologEngine.getInstance();
        assertTrue(engine.hasSolution("high_risk(tanker, hazmat_cargo)"), "80% is high-risk");
        assertFalse(engine.hasSolution("high_risk(tanker, general_cargo)"), "20% is not");
    }

    /** R9: the tonnage brackets, at both edges. */
    @Test
    void theTonnageBracketsInR9AreTheKernels() {
        assertEquals(1, one("base_tugs(19999, N)", "N").intValue());
        assertEquals(2, one("base_tugs(20000, N)", "N").intValue());
        assertEquals(2, one("base_tugs(49999, N)", "N").intValue());
        assertEquals(3, one("base_tugs(50000, N)", "N").intValue());

        assertTrue(text("R9").contains("1 below 20,000 tonnes, 2 below 50,000 tonnes, otherwise 3"), text("R9"));
    }

    /** R25: every rank the text gives is the kernel's. */
    @Test
    void theRanksInR25AreTheKernels() {
        String r25 = text("R25");
        Map<String, String> phrases = Map.of(
                "emergency", "emergency ", "medical", "medical ", "scheduled_ferry", "scheduled ferry ",
                "scheduled_arrival", "scheduled arrival ", "maintenance", "maintenance ");
        for (Map.Entry<String, String> phrase : phrases.entrySet()) {
            long rank = one("priority_rank(" + phrase.getKey() + ", R)", "R").longValue();
            assertTrue(r25.contains(phrase.getValue() + rank), r25 + " should give " + phrase.getKey() + " " + rank);
        }
        long walkIn = one("priority_rank(walk_in_arrival, R)", "R").longValue();
        assertEquals(walkIn, one("priority_rank(departure, R)", "R").longValue(), "named together in the text");
        assertTrue(r25.contains("walk-in and departure " + walkIn), r25);
    }
}
