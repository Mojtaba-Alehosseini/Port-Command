package it.unige.portcommand.assistant;

import java.util.Locale;

/**
 * Builds (a) the plain-text template fallback via {@link #template} and (b) the model-neutral
 * prompt payload via {@link #prompt} (planning/10 §10.3). Deliberately does NOT emit Phi-4
 * {@code <|system|>...<|assistant|>} tags — the Flask sidecar (task 13) applies whichever
 * model's own chat template is loaded, and Phi-4 and Gemma-3 do not share one.
 *
 * <p>Every number is rendered with {@code "%.0f"} under {@link Locale#ROOT} (explicit locale so
 * the decimal separator is always a dot); {@link HallucinationValidator} and
 * {@link Recommendation#allFigures()} depend on this exact formatting to line up.
 */
public final class AssistantPromptBuilder {

    /**
     * The anti-hallucination system instruction. Mirrored (deliberately, not shared) by
     * {@code llm_sidecar/prompts.py DEFAULT_SYSTEM_PROMPT}; Java always sends its own, so this
     * constant is the one that reaches the model in production.
     *
     * <p><b>2026-07-27 (task 26, decision a).</b> Two sentences were added naming the two figures
     * the model actually drops. Task 25 measured the gate at 2/20 with the sidecar up: the
     * generations invent nothing (check 3 held on all 20) but check 1 requires EVERY figure in
     * {@link Recommendation#allFigures()} to be reproduced, and Phi-4-mini silently omits the
     * least salient one — the SAMPLE COUNT in 18/18 failures, and the duration / mean / stddev /
     * EV in one each. Output was never truncated (~47 words of a 120-token budget), so this is a
     * choice the instruction can address rather than a budget problem. No digit appears in this
     * text on purpose: an example figure here would be copied into the answer and fail check 3
     * (the positive control admits only numbers from the recommendation itself).
     */
    public static final String SYSTEM_PROMPT = """
            You are a harbour-master negotiation assistant. Paraphrase the input recommendation
            in 2-3 sentences of clear, professional English. NEVER add, change, or omit any
            numbers, prices, percentages, durations, or named entities (berths, vessels).
            Every figure in the input must appear in your answer, including the service duration
            in hours and the number of recent deals the market figure is based on. Leaving a
            figure out is as wrong as inventing one.
            If unsure, repeat the input verbatim.""";

    private AssistantPromptBuilder() {
    }

    /** The model-neutral {@code {system, user}} payload sent to {@code LLMBridge.explain}. */
    public record PromptPayload(String system, String user) {
    }

    public static PromptPayload prompt(Recommendation rec) {
        String user = String.format(Locale.ROOT,
                "Recommend: %s.%n%nReasoning: %s.%nYour proposed action has acceptance probability "
                        + "%.0f%% and expected value €%.0f. Berth %s is %s.",
                actionPricePart(rec), marketBasisSentence(rec),
                rec.pAccept() * 100.0, rec.ev(), rec.berthId(), rec.prologStatus());
        return new PromptPayload(SYSTEM_PROMPT, user);
    }

    /** Plain-text fallback shown when the LLM is unavailable, times out, or fails validation. */
    public static String template(Recommendation rec) {
        return String.format(Locale.ROOT,
                "Recommended %s for %s at %s (%dh). %s. Acceptance probability %.0f%%, "
                        + "expected value €%.0f. Berth %s is %s.",
                actionPricePart(rec), rec.vesselType(), rec.berthId(), rec.durationHours(),
                marketBasisSentence(rec), rec.pAccept() * 100.0, rec.ev(), rec.berthId(), rec.prologStatus());
    }

    /**
     * The one clause that says what the recommendation was priced against.
     *
     * <p><b>Empty/thin market history (planning/10's filed Hint finding).</b> Below 10 samples the
     * algorithm scores against the static §7.5 fee band, not the market query — so quoting the
     * query would print "Market average is €0 (±€0, 0 recent deals)" on the first Hint of every
     * fresh run and make an otherwise-correct recommendation read as arbitrary. On that path the
     * text names the band it actually used. The figures rendered here must stay exactly the set
     * {@link Recommendation#allFigures()} publishes, in both branches — that set is the
     * hallucination validator's required-number list AND its positive control.
     */
    private static String marketBasisSentence(Recommendation rec) {
        if (rec.usesFallbackBand()) {
            // vesselType and durationHours stay in this branch on purpose: allFigures() lists the
            // duration, and check 1 requires every listed figure to appear in the generated text.
            // Dropping it here would fail every low-confidence explanation and silently pin the
            // Assistant to its template — a regression with no visible symptom but the LLM never
            // being used. Never name the spec section ("§7.5") in this sentence either: its digits
            // would be numbers the positive control has no entry for.
            return String.format(Locale.ROOT,
                    "Market history for %s %dh is too thin to price from (%d recent deals), so this "
                            + "uses the standard %s band of €%.0f to €%.0f",
                    rec.vesselType(), rec.durationHours(), rec.marketStats().sampleCount(),
                    rec.vesselType(), rec.fallbackBand().lo(), rec.fallbackBand().hi());
        }
        return String.format(Locale.ROOT,
                "Market average for %s %dh is €%.0f (±€%.0f, based on %d recent deals)",
                rec.vesselType(), rec.durationHours(), rec.marketStats().mean(),
                rec.marketStats().stddev(), rec.marketStats().sampleCount());
    }

    private static String actionPricePart(Recommendation rec) {
        if (RecommendationCandidate.REJECT_SILENT.equals(rec.action())) {
            return "reject silently";
        }
        return rec.action() + " at €" + String.format(Locale.ROOT, "%.0f", rec.price());
    }
}
