package it.unige.portcommand.nlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import it.unige.portcommand.prolog.PrologTerms;

/**
 * Turns a raw chat line into the Prolog token list {@code dcg_negotiation.pl} expects.
 *
 * <p><b>The tokeniser owns ALL normalisation.</b> The grammar never lowercases or rewrites
 * anything — per the project's tokenisation contract, duplicating normalisation in Prolog
 * would make case mismatches fail silently. Everything the grammar's terminals match
 * ({@code eur}, {@code h}, {@code '14:20'}) is produced here, in this order:
 *
 * <ol>
 *   <li>lowercase, and fold the typographic apostrophe (U+2019) to ASCII {@code '} — a line
 *       pasted from a word processor must tokenise identically to one typed in the chat box;</li>
 *   <li>drop thousands separators ({@code 2,000} &rarr; {@code 2000}) — the punctuation strip
 *       below would otherwise split it into two tokens, {@code 2} and {@code 000};</li>
 *   <li>normalise currency and duration BEFORE any stripping, so the grammar sees clean
 *       tokens: {@code €2000}/{@code 2000€} &rarr; {@code 2000 eur}, {@code $2000}/{@code 2000$}
 *       &rarr; {@code 2000 usd}, {@code 5h}/{@code 5hrs}/{@code 5hours} &rarr; {@code 5 h};</li>
 *   <li>strip stray punctuation, preserving three characters that carry meaning:
 *       <ul>
 *         <li>{@code :} — clock times ({@code 14:20} stays ONE token);</li>
 *         <li>{@code '} — clitics and names ({@code i'll}, {@code o'brien}) stay one token,
 *             which is what makes {@link PrologTerms#escape} load-bearing rather than
 *             decorative (see {@link #toPrologList});</li>
 *         <li>{@code .} — but only between digits, so {@code 1800.50} survives as one number
 *             while a sentence-final {@code please.} does not become the atom {@code 'please.'}.
 *             A dropped decimal would silently turn "1800.50" into a 1800 offer with a stray
 *             50 — the same silent-slot-corruption class the grammar's mention-guards prevent.</li>
 *       </ul></li>
 *   <li>split a {@code :} that is NOT between digits into its own token, so a vocative
 *       address ({@code "Genoa Star: 2000"}) yields {@code [genoa, star, :, 2000]} rather than
 *       a fused {@code 'star:'} atom, while {@code 14:20} is left alone.</li>
 * </ol>
 *
 * <p>Deviation from planning/16 Step 16.3, recorded in ADR-10: that sketch strips with
 * {@code [^a-z0-9: ]}, which also deletes apostrophes — "I'll pay 1800" would tokenise to
 * {@code [i, ll, pay, 1800]} and no atom could ever contain a quote, making the
 * apostrophe-escaping the brief explicitly asks to unit-test unreachable in production, i.e.
 * vacuous. Keeping {@code '} also matches Rasa's {@code WhitespaceTokenizer} (task 12), so the
 * two NLU paths agree on token boundaries.
 */
public final class DcgTokenizer {

    /** {@code 2,000} -> {@code 2000}. Bounded to exactly-three-digit groups so an ordinary
     * comma ("2000, please") is left for the punctuation strip to turn into a separator. */
    private static final Pattern THOUSANDS_SEPARATOR = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");

    /** Each captures the WHOLE number, not just the adjacent digit: the replacement appends the
     * currency token after the amount, so {@code €2000} must capture {@code 2000} — capturing
     * only {@code 2} yields {@code "2 eur000"}. Thousands separators are already gone by here. */
    private static final Pattern EUR_PREFIX = Pattern.compile("€\\s*(\\d[\\d.]*)");
    private static final Pattern EUR_SUFFIX = Pattern.compile("(\\d[\\d.]*)\\s*€");
    private static final Pattern USD_PREFIX = Pattern.compile("\\$\\s*(\\d[\\d.]*)");
    private static final Pattern USD_SUFFIX = Pattern.compile("(\\d[\\d.]*)\\s*\\$");

    /** {@code 5h} / {@code 5hrs} / {@code 5hours} -> {@code 5 h}. The trailing {@code \b} keeps
     * this off a number that merely precedes an h-word ("2000 harbour" is untouched). */
    private static final Pattern HOUR_UNIT = Pattern.compile("(\\d)\\s*h(?:rs?|ours?)?\\b");

    /** {@code 10%} -> {@code 10 percent} (16-M2 delta block: "10% less"). Runs BEFORE the
     * stray-punctuation strip, which would otherwise delete the {@code %} and silently turn
     * "10% less" into a "10 less" (subtract 10) — the same slot-corruption class the grammar's
     * mention-guards prevent. The grammar's {@code percent} terminal then matches the word. */
    private static final Pattern PERCENT_UNIT = Pattern.compile("(\\d)\\s*%");

    /** {@code berth2} -> {@code berth 2} (task 19 play-test: players type the fused form —
     * the UI itself displays ids like {@code berth_1}, and {@code _} already splits via the
     * punctuation strip, but {@code berth2} stayed one token the grammar's {@code [berth, N]}
     * terminals could never match). */
    private static final Pattern BERTH_FUSED = Pattern.compile("\\bberth(\\d)");

    /** Everything outside this set becomes a separator. See the class javadoc for why
     * {@code :}, {@code '} and {@code .} survive. */
    private static final Pattern STRAY_PUNCTUATION = Pattern.compile("[^a-z0-9:'. ]");

    private static final Pattern DOT_NOT_AFTER_DIGIT = Pattern.compile("(?<!\\d)\\.");
    private static final Pattern DOT_NOT_BEFORE_DIGIT = Pattern.compile("\\.(?!\\d)");

    private static final Pattern COLON_NOT_AFTER_DIGIT = Pattern.compile("(?<!\\d):");
    private static final Pattern COLON_NOT_BEFORE_DIGIT = Pattern.compile(":(?!\\d)");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** A Prolog number literal: emitted bare into the goal, never quoted. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * @param text the raw chat line; {@code null}/blank yields an empty list
     * @return lowercase tokens, normalised for the grammar
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String t = text.toLowerCase(Locale.ROOT).replace('’', '\'');

        t = THOUSANDS_SEPARATOR.matcher(t).replaceAll("");

        t = EUR_PREFIX.matcher(t).replaceAll("$1 eur");
        t = EUR_SUFFIX.matcher(t).replaceAll("$1 eur");
        t = USD_PREFIX.matcher(t).replaceAll("$1 usd");
        t = USD_SUFFIX.matcher(t).replaceAll("$1 usd");
        t = HOUR_UNIT.matcher(t).replaceAll("$1 h");
        t = PERCENT_UNIT.matcher(t).replaceAll("$1 percent");
        t = BERTH_FUSED.matcher(t).replaceAll("berth $1");

        t = STRAY_PUNCTUATION.matcher(t).replaceAll(" ");
        t = DOT_NOT_AFTER_DIGIT.matcher(t).replaceAll(" ");
        t = DOT_NOT_BEFORE_DIGIT.matcher(t).replaceAll(" ");
        t = COLON_NOT_AFTER_DIGIT.matcher(t).replaceAll(" : ");
        t = COLON_NOT_BEFORE_DIGIT.matcher(t).replaceAll(" : ");

        List<String> tokens = new ArrayList<>();
        for (String token : WHITESPACE.split(t.trim())) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    /**
     * Renders tokens as a Prolog list literal: numbers bare, everything else as a quoted atom
     * with backslashes and single quotes escaped (via {@link PrologTerms#quoteAtom}, the same
     * helper the rule-kernel facade uses — a vessel named {@code o'brien} must not be able to
     * terminate the goal string early and inject a term).
     */
    public String toPrologList(List<String> tokens) {
        return tokens.stream()
                .map(DcgTokenizer::toPrologToken)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String toPrologToken(String token) {
        return NUMBER.matcher(token).matches() ? token : PrologTerms.quoteAtom(token);
    }
}
