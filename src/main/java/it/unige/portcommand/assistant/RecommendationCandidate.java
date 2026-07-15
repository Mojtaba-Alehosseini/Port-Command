package it.unige.portcommand.assistant;

/**
 * One of the four proposal moves {@link RecommendationAlgorithm} scores: {@code "accept"},
 * {@code "counter"}, or {@code "reject_silent"}. {@code price} is already clamped to the
 * observable market band (or the {@code IncomeRules} fallback band) before scoring;
 * {@code marginalCost} is the Java-computed cost of servicing the deal ({@code ExpenseRules}).
 */
public record RecommendationCandidate(String action, double price, double marginalCost) {

    public static final String ACCEPT = "accept";
    public static final String COUNTER = "counter";
    public static final String REJECT_SILENT = "reject_silent";
}
