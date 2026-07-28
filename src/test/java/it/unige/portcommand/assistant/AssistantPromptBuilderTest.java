package it.unige.portcommand.assistant;

import java.util.List;

import it.unige.portcommand.artifacts.MarketStats;
import it.unige.portcommand.harbourmaster.financial.IncomeRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Assistant's two surfaced texts — the LLM prompt and the plain-text template.
 *
 * <p>Carries the regression for the <b>empty-market Hint</b> finding filed in planning/10 by the
 * task-24 visual checkpoint: on the first Hint of a fresh run the market history is empty, the
 * algorithm correctly falls back to the static §7.5 fee band, but the explanation quoted the
 * (zeroed) market query instead — "Market average €0 (±€0, 0 recent deals)" — so a sound
 * recommendation read as arbitrary. Fixed in task 25 by carrying the band the algorithm actually
 * scored against into {@link Recommendation} and naming it in the text.
 */
class AssistantPromptBuilderTest {

    /** A fresh run: nothing has closed yet, so the market query is empty and low-confidence. */
    private static Recommendation freshRunCounter() {
        MarketStats empty = MarketStats.empty();
        IncomeRules.PriceRange band = IncomeRules.berthFeeRange("berth_3", "cargo_vessel");
        return new Recommendation(RecommendationCandidate.COUNTER, band.lo(), 0.35, 274.0,
                List.of("R7"), empty, "cargo_vessel", "berth_3", 5, "compatible", band);
    }

    /** A mature run: 14 closed deals back the estimate, so the live market query is used. */
    private static Recommendation maturedCounter() {
        MarketStats stats = new MarketStats(2050.0, 120.0, 14, 1800.0, 2300.0, 0.7, false);
        return new Recommendation(RecommendationCandidate.COUNTER, 1900.0, 0.62, 950.0,
                List.of("R7"), stats, "cargo_vessel", "berth_3", 5, "compatible", null);
    }

    // --- the filed finding ----------------------------------------------------------------------

    @Test
    void anEmptyMarketNamesTheFallbackBandInsteadOfQuotingAZeroAverage() {
        String template = AssistantPromptBuilder.template(freshRunCounter());

        assertFalse(template.contains("Market average"),
                () -> "an empty history has no average to quote: " + template);
        assertTrue(template.contains("1400") && template.contains("2200"),
                () -> "the cargo_vessel §7.5 band (1400-2200) must be surfaced: " + template);
        assertTrue(template.contains("too thin"),
                () -> "the player must be told WHY there is no average: " + template);
    }

    @Test
    void anEmptyMarketPromptAlsoNamesTheBand() {
        String user = AssistantPromptBuilder.prompt(freshRunCounter()).user();

        assertFalse(user.contains("Market average"), () -> user);
        assertTrue(user.contains("1400") && user.contains("2200"), () -> user);
    }

    @Test
    void aMaturedMarketStillQuotesTheLiveAverage() {
        String template = AssistantPromptBuilder.template(maturedCounter());

        assertTrue(template.contains("Market average"), () -> template);
        assertTrue(template.contains("2050") && template.contains("120") && template.contains("14"),
                () -> "mean, stddev and sample count all come from the live query: " + template);
        assertFalse(template.contains("too thin"), () -> template);
    }

    // --- the property that keeps the LLM path alive ----------------------------------------------

    /**
     * The prompt is the LLM's only source of figures, and {@link HallucinationValidator} accepts an
     * answer only if EVERY figure in {@link Recommendation#allFigures()} appears in it (check 1) and
     * NO other number does (check 3). So a faithful paraphrase — the prompt's own words — must
     * survive the validator. If it does not, the strictest possible model still fails validation,
     * every explanation silently degrades to the template, and the LLM becomes dead code with no
     * visible symptom. Asserted on BOTH market branches because the fallback branch is exactly where
     * a dropped figure (the duration) would have gone unnoticed.
     */
    @Test
    void aFaithfulParaphraseOfThePromptSurvivesTheValidatorOnBothMarketBranches() {
        for (Recommendation rec : List.of(freshRunCounter(), maturedCounter())) {
            String user = AssistantPromptBuilder.prompt(rec).user();
            assertTrue(HallucinationValidator.validate(user, rec),
                    () -> "the prompt's own text must validate, else the LLM path is unreachable: " + user);
        }
    }

    @Test
    void theTemplateFallbackAlsoSurvivesTheValidatorOnBothMarketBranches() {
        for (Recommendation rec : List.of(freshRunCounter(), maturedCounter())) {
            String template = AssistantPromptBuilder.template(rec);
            assertTrue(HallucinationValidator.validate(template, rec), () -> template);
        }
    }

    @Test
    void aSilentRejectionNamesNoPrice() {
        Recommendation reject = new Recommendation(RecommendationCandidate.REJECT_SILENT, 0.0, 0.0, 0.0,
                List.of("R7"), MarketStats.empty(), "ferry", "berth_1", 3, "incompatible",
                IncomeRules.berthFeeRange("berth_1", "ferry"));

        String template = AssistantPromptBuilder.template(reject);

        assertTrue(template.contains("reject silently"), () -> template);
        assertTrue(template.contains("incompatible"), () -> template);
        assertTrue(HallucinationValidator.validate(template, reject), () -> template);
    }
}
