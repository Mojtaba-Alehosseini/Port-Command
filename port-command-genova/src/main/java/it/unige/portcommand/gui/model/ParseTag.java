package it.unige.portcommand.gui.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

import it.unige.portcommand.nlp.ParseProvenance;

/**
 * Pure model for the small grey note after a player's chat message (S15, 2026-10-12): which stage
 * of the NLP pipeline read it — {@code (DCG)} when the Prolog grammar parsed the sentence,
 * {@code (Rasa 0.97)} when the grammar missed and Rasa's classifier routed it, with the confidence
 * Rasa reported. No Swing/AWT import; {@code DialogueTabModel.tagPlayerMessage} stores the text
 * and {@code ChatMessageRenderer} draws it.
 *
 * <p><b>Only routed turns are tagged.</b> A turn that ended in "I didn't get that" or "I couldn't
 * parse that" was acted on by neither stage, and the line saying so sits right below it; a result
 * that does not state its source ({@link ParseProvenance#UNKNOWN} — test doubles, older callers)
 * gets no tag rather than a guessed one. Both give the empty string here.
 *
 * <p><b>Truncated, not rounded.</b> The accept floor in the pipeline is 0.95 and the measured
 * false accepts sit at 0.98 and above, so the last hundredth is the part people read. Rounding
 * would print a 0.9997 parse as {@code 1.00} — a certainty Rasa never claimed — and would lift a
 * 0.949 parse to {@code 0.95}, the floor it did not reach. Cutting at two decimals never shows a
 * number higher than the real one.
 */
public final class ParseTag {

    /** The tag for a sentence the grammar parsed. */
    public static final String DCG_TEXT = "(DCG)";
    /** The tag for a Rasa route whose confidence is not a finite number. */
    public static final String RASA_TEXT = "(Rasa)";

    private ParseTag() {
    }

    /**
     * @param provenance where a routed result came from, or {@code null}
     * @return {@code ""} (no tag) for {@code null} or an unknown source, {@code (DCG)}, or
     *         {@code (Rasa 0.97)}; {@code (Rasa)} when Rasa routed the turn but the confidence is
     *         not a finite number
     */
    public static String text(ParseProvenance provenance) {
        if (provenance == null || provenance.stage() == null) {
            return "";
        }
        return switch (provenance.stage()) {
            case UNKNOWN -> "";
            case DCG -> DCG_TEXT;
            case RASA -> rasa(provenance.confidence());
        };
    }

    private static String rasa(double confidence) {
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return RASA_TEXT;
        }
        // BigDecimal.valueOf goes through the double's shortest decimal form ("0.97"), so 0.97
        // stays 0.97 — new BigDecimal(0.97) is 0.96999… and would truncate to 0.96. No Formatter
        // and no Locale is involved: the separator is always '.', on every machine.
        return "(Rasa " + BigDecimal.valueOf(confidence).setScale(2, RoundingMode.DOWN).toPlainString() + ")";
    }
}
