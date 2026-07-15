package it.unige.portcommand.assistant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import it.unige.portcommand.artifacts.MarketStats;

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
        String prologStatus) {

    public Recommendation {
        prologTrace = List.copyOf(prologTrace);
    }

    /** Proper nouns legitimately present in the recommendation trace — the negative-control gazetteer. */
    public Set<String> namedEntities() {
        return Set.of(berthId);
    }

    /**
     * Every numeric figure that may legitimately appear in the LLM's paraphrase, formatted
     * exactly as {@link AssistantPromptBuilder} renders them ({@code "%.0f"}, {@link Locale#ROOT})
     * so {@link HallucinationValidator}'s normalised-string comparison lines up.
     */
    public Set<String> allFigures() {
        Set<String> figures = new LinkedHashSet<>();
        figures.add(fmt(marketStats.mean()));
        figures.add(fmt(marketStats.stddev()));
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
