package it.unige.portcommand.nlp;

/**
 * Which stage of the pipeline produced a routed turn, and how sure it was (S15): the facts behind
 * the small grey {@code (DCG)} / {@code (Rasa 0.97)} tag under a player's message.
 *
 * <p>Reporting only. It is attached to a result AFTER routing has been decided and nothing reads
 * it to decide anything — the cascade (DCG first, Rasa fallback, the confidence gates) is
 * unchanged.
 *
 * @param stage      the stage that produced the result
 * @param confidence Rasa's intent confidence in [0,1]; {@code NaN} for the DCG (a grammar either
 *                   parses or does not) and for an unknown source
 */
public record ParseProvenance(Stage stage, double confidence) {

    public enum Stage { UNKNOWN, DCG, RASA }

    /** No source recorded — a result built by a test double, or by code older than this record. */
    public static final ParseProvenance UNKNOWN = new ParseProvenance(Stage.UNKNOWN, Double.NaN);
    /** The Prolog grammar parsed the sentence. */
    public static final ParseProvenance DCG = new ParseProvenance(Stage.DCG, Double.NaN);

    public static ParseProvenance rasa(double confidence) {
        return new ParseProvenance(Stage.RASA, confidence);
    }
}
