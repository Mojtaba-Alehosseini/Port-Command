package it.unige.portcommand.nlp;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex pre-pass over raw chat text (PROJECT_DEFINITION.md §6.1: "Tokenisation + regex
 * pre-pass (vessel names, prices, time)"). A fast, best-effort extraction of six informal-
 * negotiation fields — never the source of truth. The DCG (task 16) and Rasa's DIET NER
 * (holdout F1 0.97, task 12) own the authoritative extraction; a miss here just means these
 * fields stay {@link Optional#empty()} and the downstream stages fall back as normal.
 */
public final class PreprocessRegex {

    private static final Pattern DURATION =
            Pattern.compile("\\b(\\d{1,2})\\s?(?:h|hrs?|hours?)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PRICE_SYMBOL_PREFIX = Pattern.compile("[€$]\\s?(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern PRICE_SYMBOL_SUFFIX = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s?[€$]");
    private static final Pattern PRICE_K_SUFFIX =
            Pattern.compile("\\b(\\d[\\d,]*(?:\\.\\d+)?)\\s?k\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_WORD_SUFFIX =
            Pattern.compile("\\b(\\d[\\d,]*(?:\\.\\d+)?)\\s?(?:euros?|eur|dollars?)\\b", Pattern.CASE_INSENSITIVE);
    // Bare fallback: needs >= 3 digits, so it never collides with a 1-2 digit duration/berth number.
    private static final Pattern PRICE_BARE = Pattern.compile("\\b(\\d[\\d,]{2,}(?:\\.\\d+)?)\\b");
    private static final Pattern PRICE_BARE_FOLLOWED_BY_HOUR_UNIT =
            Pattern.compile("^\\s?(?:h|hrs?|hours?)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern BERTH_DIGIT =
            Pattern.compile("\\b(?:berth|dock|quay|pier|slip)\\s*#?\\s*(\\d)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BERTH_WORD = Pattern.compile(
            "\\b(?:berth|dock|quay|pier|slip)\\s+(one|two|three|four)\\b", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> WORD_NUMBERS =
            Map.of("one", "1", "two", "2", "three", "3", "four", "4");

    // Both require a disambiguator (colon-minutes or am/pm) — a bare "by 5" is too easily a
    // false positive ("reduce by 5 berths") to treat as a time, mirroring TIME_COLON/TIME_BARE_AMPM.
    private static final Pattern DEADLINE_COLON = Pattern.compile(
            "\\b(?:by|before|until)\\s+([01]?\\d|2[0-3]):([0-5]\\d)\\s?(am|pm)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEADLINE_BARE_AMPM = Pattern.compile(
            "\\b(?:by|before|until)\\s+(\\d{1,2})\\s?(am|pm)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_COLON =
            Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\s?(am|pm)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_BARE_AMPM =
            Pattern.compile("\\b(\\d{1,2})\\s?(am|pm)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern VOCATIVE_NAME =
            Pattern.compile("^\\s*([A-Z][a-zA-Z]+(?:\\s[A-Z][a-zA-Z]+)*)\\s*:");
    private static final Pattern THE_NAME =
            Pattern.compile("\\bthe\\s+([A-Z][a-zA-Z]+(?:\\s[A-Z][a-zA-Z]+)*)\\b");

    public Extracted extract(String text) {
        if (text == null || text.isBlank()) {
            return Extracted.NONE;
        }
        Optional<Integer> duration = extractDuration(text);
        Optional<Double> price = extractPrice(text);
        Optional<String> berthId = extractBerth(text);
        Optional<LocalTime> deadline = extractDeadline(text);
        Optional<LocalTime> time = extractTime(text);
        Optional<String> vesselName = extractVesselName(text);
        return new Extracted(price, duration, time, deadline, berthId, vesselName);
    }

    private static Optional<Integer> extractDuration(String text) {
        Matcher m = DURATION.matcher(text);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private static Optional<Double> extractPrice(String text) {
        // k-suffix and word-suffix are tried FIRST: for "$2k", the symbol pattern below would
        // otherwise grab just "2" (stopping before the "k") and drop the x1000 multiplier.
        Matcher k = PRICE_K_SUFFIX.matcher(text);
        if (k.find()) {
            return Optional.of(parseNumber(k.group(1)) * 1000.0);
        }
        Matcher word = PRICE_WORD_SUFFIX.matcher(text);
        if (word.find()) {
            return Optional.of(parseNumber(word.group(1)));
        }
        for (Pattern symbolForm : List.of(PRICE_SYMBOL_PREFIX, PRICE_SYMBOL_SUFFIX)) {
            Matcher m = symbolForm.matcher(text);
            if (m.find()) {
                return Optional.of(parseNumber(m.group(1)));
            }
        }
        Matcher bare = PRICE_BARE.matcher(text);
        while (bare.find()) {
            String rest = text.substring(bare.end());
            if (!PRICE_BARE_FOLLOWED_BY_HOUR_UNIT.matcher(rest).lookingAt()) {
                return Optional.of(parseNumber(bare.group(1)));
            }
        }
        return Optional.empty();
    }

    private static double parseNumber(String raw) {
        return Double.parseDouble(raw.replace(",", ""));
    }

    private static Optional<String> extractBerth(String text) {
        Matcher digit = BERTH_DIGIT.matcher(text);
        if (digit.find()) {
            return Optional.of("berth_" + digit.group(1));
        }
        Matcher word = BERTH_WORD.matcher(text);
        if (word.find()) {
            return Optional.of("berth_" + WORD_NUMBERS.get(word.group(1).toLowerCase()));
        }
        return Optional.empty();
    }

    private static Optional<LocalTime> extractDeadline(String text) {
        Matcher colon = DEADLINE_COLON.matcher(text);
        if (colon.find()) {
            return Optional.of(toLocalTime(colon.group(1), colon.group(2), colon.group(3)));
        }
        Matcher bareAmPm = DEADLINE_BARE_AMPM.matcher(text);
        if (bareAmPm.find()) {
            return Optional.of(toLocalTime(bareAmPm.group(1), null, bareAmPm.group(2)));
        }
        return Optional.empty();
    }

    private static Optional<LocalTime> extractTime(String text) {
        Optional<LocalTime> viaColon = firstNonDeadlineMatch(TIME_COLON, text, true);
        return viaColon.isPresent() ? viaColon : firstNonDeadlineMatch(TIME_BARE_AMPM, text, false);
    }

    /** Skips a match whose preceding clause is {@code by}/{@code before}/{@code until} — that
     * occurrence belongs to {@link #extractDeadline}, not the plain "time" field. */
    private static Optional<LocalTime> firstNonDeadlineMatch(Pattern pattern, String text, boolean hasMinuteGroup) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int clauseStart = Math.max(0, m.start() - 8);
            String before = text.substring(clauseStart, m.start()).toLowerCase();
            if (before.matches("(?s).*\\b(by|before|until)\\s*$")) {
                continue;
            }
            String hour = m.group(1);
            String minute = hasMinuteGroup ? m.group(2) : null;
            String ampm = hasMinuteGroup ? m.group(3) : m.group(2);
            return Optional.of(toLocalTime(hour, minute, ampm));
        }
        return Optional.empty();
    }

    private static LocalTime toLocalTime(String hourStr, String minuteStr, String ampm) {
        int hour = Integer.parseInt(hourStr);
        int minute = minuteStr != null ? Integer.parseInt(minuteStr) : 0;
        if (ampm != null) {
            hour = hour % 12 + (ampm.equalsIgnoreCase("pm") ? 12 : 0);
        }
        return LocalTime.of(hour % 24, minute);
    }

    private static Optional<String> extractVesselName(String text) {
        Matcher vocative = VOCATIVE_NAME.matcher(text);
        if (vocative.find()) {
            return Optional.of(vocative.group(1));
        }
        Matcher theName = THE_NAME.matcher(text);
        if (theName.find()) {
            return Optional.of(theName.group(1));
        }
        return Optional.empty();
    }

    /** The six pre-pass fields (planning/14 §14.3); a miss is {@link Optional#empty()}, never null. */
    public record Extracted(
            Optional<Double> price,
            Optional<Integer> duration,
            Optional<LocalTime> time,
            Optional<LocalTime> deadline,
            Optional<String> berthId,
            Optional<String> vesselName) {

        static final Extracted NONE = new Extracted(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
