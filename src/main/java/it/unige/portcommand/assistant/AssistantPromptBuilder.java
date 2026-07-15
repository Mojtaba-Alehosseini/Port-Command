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

    public static final String SYSTEM_PROMPT = """
            You are a harbour-master negotiation assistant. Paraphrase the input recommendation
            in 2-3 sentences of clear, professional English. NEVER add, change, or omit any
            numbers, prices, percentages, durations, or named entities (berths, vessels).
            If unsure, repeat the input verbatim.""";

    private AssistantPromptBuilder() {
    }

    /** The model-neutral {@code {system, user}} payload sent to {@code LLMBridge.explain}. */
    public record PromptPayload(String system, String user) {
    }

    public static PromptPayload prompt(Recommendation rec) {
        String user = String.format(Locale.ROOT,
                "Recommend: %s.%n%nReasoning: Market average for %s %dh is €%.0f (±€%.0f, based on "
                        + "%d recent deals).%nYour proposed action has acceptance probability %.0f%% "
                        + "and expected value €%.0f. Berth %s is %s.",
                actionPricePart(rec), rec.vesselType(), rec.durationHours(), rec.marketStats().mean(),
                rec.marketStats().stddev(), rec.marketStats().sampleCount(), rec.pAccept() * 100.0, rec.ev(),
                rec.berthId(), rec.prologStatus());
        return new PromptPayload(SYSTEM_PROMPT, user);
    }

    /** Plain-text fallback shown when the LLM is unavailable, times out, or fails validation. */
    public static String template(Recommendation rec) {
        return String.format(Locale.ROOT,
                "Recommended %s for %s at %s (%dh). Market average is €%.0f (±€%.0f, %d recent deals). "
                        + "Acceptance probability %.0f%%, expected value €%.0f. Berth %s is %s.",
                actionPricePart(rec), rec.vesselType(), rec.berthId(), rec.durationHours(),
                rec.marketStats().mean(), rec.marketStats().stddev(), rec.marketStats().sampleCount(),
                rec.pAccept() * 100.0, rec.ev(), rec.berthId(), rec.prologStatus());
    }

    private static String actionPricePart(Recommendation rec) {
        if (RecommendationCandidate.REJECT_SILENT.equals(rec.action())) {
            return "reject silently";
        }
        return rec.action() + " at €" + String.format(Locale.ROOT, "%.0f", rec.price());
    }
}
