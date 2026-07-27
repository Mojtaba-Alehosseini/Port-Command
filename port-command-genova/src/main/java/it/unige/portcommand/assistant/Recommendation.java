package it.unige.portcommand.assistant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import it.unige.portcommand.artifacts.MarketStats;
import it.unige.portcommand.harbourmaster.financial.IncomeRules;

/**
 * The chosen action from {@link RecommendationAlgorithm#run}, plus everything
 * {@link AssistantPromptBuilder} and {@link HallucinationValidator} need to build and check the
 * natural-language explanation: the source figures ({@link #allFigures()}, the positive
 * control) and the source proper nouns ({@link #namedEntities()}, the gazetteer for the
 * negative control).
 *
 * @param action       {@link RecommendationCandidate#ACCEPT}, {@link RecommendationCandidate#COUNTER},
 *                     or {@link RecommendationCandidate#REJECT_SILENT}
 * @param price        the recommended price (0.0 for {@code reject_silent})
 * @param pAccept      acceptance probability in [0, 1] (1.0 for the deterministic {@code accept} case)
 * @param ev           expected value of the chosen action
 * @param prologTrace  Prolog rules actually cited (e.g. {@code "R7"} for the compatibility check);
 *                     Java-computed figures (fee range, marginal cost) are never cited as {@code Rn}
 * @param marketStats  the market stats the algorithm scored against (real query or the low-confidence
 *                     synthesised fallback)
 * @param vesselType   the vessel type under negotiation
 * @param berthId      the berth under negotiation
 * @param durationHours the negotiated service duration
 * @param prologStatus {@code "compatible"} or {@code "incompatible"} — the berth/vessel Prolog verdict
 * @param fallbackBand the static §7.5 fee band the algorithm actually scored against when the
 *                     market query was low-confidence (&lt; 10 samples), else {@code null}. Carried
 *                     rather than re-derived so {@link AssistantPromptBuilder} can NAME the band it
 *                     used instead of printing the zeroed {@code marketStats} — the task-24
 *                     visual-checkpoint finding filed in planning/10 ("Market average €0 (±€0, 0
 *                     recent deals)" on the first Hint of a fresh run). Passing it through is what
 *                     makes the surfaced text and the scoring band structurally the same number.
 */
public record Recommendation(
        String action,
        double price,
        double pAccept,
        double ev,
        List<String> prologTrace,
        MarketStats marketStats,
        String vesselType,
        String berthId,
        int durationHours,
        String prologStatus,
        IncomeRules.PriceRange fallbackBand) {

    public Recommendation {
        prologTrace = List.copyOf(prologTrace);
    }

    /** True when the recommendation was scored against the static §7.5 band rather than live
     * market history — i.e. when the explanation must say so instead of quoting a €0 average. */
    public boolean usesFallbackBand() {
        return fallbackBand != null;
    }

    /** Proper nouns legitimately present in the recommendation trace — the negative-control gazetteer. */
    public Set<String> namedEntities() {
        return Set.of(berthId);
    }

    /**
     * The figures a paraphrase MUST reproduce — the decision itself: the price, the acceptance
     * probability, and the expected value. Check 1 of {@link HallucinationValidator}.
     *
     * <p><b>2026-07-27 (task 26, decision a).</b> Check 1 used to require the whole of
     * {@link #allFigures()}. Measured against the real sidecar (Phi-4-mini, ONNX-INT4), that gate
     * scored 2/20; after task 26 named the dropped figures in the system prompt it scored 15/20,
     * and the residual failures were all the same shape — the model paraphrases the least salient
     * supporting figure into words instead of digits ("the lack of recent deals" for a sample count
     * of 3, or a market average quoted without its spread). Those answers are faithful; they are
     * not hallucinations. Requiring a small model to echo eight figures verbatim measures
     * instruction-following, not truthfulness, and failing on it pins the Assistant to its template
     * for no safety gain.
     *
     * <p>What is NOT relaxed: {@link #allFigures()} stays the positive control, so a number that
     * is neither in this set nor anywhere else in the recommendation still fails check 3. Two of
     * the twenty traces still fail exactly there, and correctly: asked for a 0% acceptance
     * probability, the model writes "100% likelihood of rejection", and 100 is a number the
     * recommendation never contained.
     *
     * <p><b>The precise cost, stated because an adversarial review found the earlier wording
     * ("may never alter it") too strong.</b> A supporting figure may now be omitted, OR replaced
     * by another figure <i>of the same recommendation</i>: "based on 500 recent deals" passes
     * for a trace whose sample count is 12 but whose stddev is 500, because check 1 no longer
     * demands the 12 and check 3 admits the 500. It may still never be replaced by a number the
     * recommendation does not contain, which is the case that matters — an invented or
     * arbitrarily wrong figure. Pinned by
     * {@code HallucinationValidatorTest.outputSwappingASupportingFigureForAnotherOfTheSameTraceIsAcceptedNow}.
     */
    public Set<String> requiredFigures() {
        Set<String> figures = new LinkedHashSet<>();
        figures.add(fmt(price));
        figures.add(fmt(pAccept * 100.0));
        figures.add(fmt(ev));
        return figures;
    }

    /**
     * Every numeric figure that may legitimately appear in the LLM's paraphrase, formatted
     * exactly as {@link AssistantPromptBuilder} renders them ({@code "%.0f"}, {@link Locale#ROOT})
     * so {@link HallucinationValidator}'s normalised-string comparison lines up. This is the
     * POSITIVE CONTROL (check 3): a number outside this set was invented or altered. A superset
     * of {@link #requiredFigures()}.
     */
    public Set<String> allFigures() {
        Set<String> figures = new LinkedHashSet<>();
        // Exactly the figures the prompt/template renders, and no others: this set is BOTH the
        // required-number list (check 1: each must appear) and the positive control (check 3: no
        // other number may appear). Emitting mean/stddev on the fallback path — where the text
        // deliberately does not print them — would make every low-confidence explanation fail
        // check 1 and silently degrade the Assistant to its template.
        if (usesFallbackBand()) {
            figures.add(fmt(fallbackBand.lo()));
            figures.add(fmt(fallbackBand.hi()));
        } else {
            figures.add(fmt(marketStats.mean()));
            figures.add(fmt(marketStats.stddev()));
        }
        figures.add(String.valueOf(marketStats.sampleCount()));
        figures.add(fmt(price));
        figures.add(fmt(pAccept * 100.0));
        figures.add(fmt(ev));
        figures.add(String.valueOf(durationHours));
        String berthDigits = berthId.replaceAll("\\D+", "");
        if (!berthDigits.isEmpty()) {
            figures.add(berthDigits);
        }
        return figures;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
