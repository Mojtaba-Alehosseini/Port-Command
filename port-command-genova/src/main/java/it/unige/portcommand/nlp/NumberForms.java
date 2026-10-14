package it.unige.portcommand.nlp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a typed number is read before either NLU path sees it (S18a, ADR-23, decision N2).
 *
 * <p>The game is set in Genova, and a European writes one thousand nine hundred as {@code 1.900}.
 * Both paths kept the dot as a decimal point: the grammar was handed the Prolog float 1.9 and the
 * regex pre-pass a price of 1.9 — an offer of EUR 1.90, which the vessel accepts at once. A dot
 * between digits is therefore read as a thousands separator when what follows it is a group of
 * exactly three digits, and as a decimal point otherwise ({@code 1800.50}, {@code 1.5}).
 *
 * <p>One method, used by {@link DcgTokenizer} and {@link PreprocessRegex}, so the two can never
 * disagree about whether a sentence carries a price or what it is.
 */
public final class NumberForms {

    /**
     * {@code 1.900}, {@code 12.500}, {@code 1.900.000}: one to three digits not starting with 0,
     * then one or more groups of exactly three digits, each behind a dot — and no more DIGITS on
     * either side, so {@code 1.9000}, {@code 0.500} and {@code 1.900.50} are left alone. A dot
     * that is only punctuation does not count: "make it 2.200." ends a sentence, and "hmm...1.900"
     * follows an ellipsis (the first version refused both and read 2.2 and 1.9).
     *
     * <p>Group 2 is the decimal comma that goes with a thousands dot ({@code 1.900,50} is
     * 1900.50) — one or two digits and then no word character, so "1.900,6h" stays a price, a
     * comma and six hours. A {@code k} behind the number means it was already written in
     * thousands ({@code 1.500k} is 1500, as it always was) and nothing is collapsed.
     */
    private static final Pattern EUROPEAN_THOUSANDS = Pattern.compile(
            "(?<!\\d)(?<!\\d\\.)([1-9]\\d{0,2}(?:\\.\\d{3})+)(?:,(\\d{1,2})(?!\\w))?(?!\\d|\\.\\d|[kK]\\b)");

    private NumberForms() {
    }

    /** {@code 1.900} becomes {@code 1900} and {@code 1.900,50} becomes {@code 1900.50}; every
     * other character is returned as it came. */
    public static String collapseEuropeanThousands(String text) {
        if (text == null || text.indexOf('.') < 0) {
            return text;
        }
        Matcher m = EUROPEAN_THOUSANDS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String whole = m.group(1).replace(".", "");
            m.appendReplacement(out, Matcher.quoteReplacement(
                    m.group(2) == null ? whole : whole + "." + m.group(2)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
