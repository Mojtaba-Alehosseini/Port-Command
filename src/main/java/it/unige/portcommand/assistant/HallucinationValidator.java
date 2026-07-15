package it.unige.portcommand.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gazetteer-bounded hallucination guard (planning/10 §10.4, v1.1 fixes). Mirrors the Python
 * sidecar's {@code llm_sidecar.validator.HallucinationValidator} (task 13) for checks 1-2, and
 * additionally runs the positive-control (check 3) that only the Java side can — it needs
 * {@link Recommendation#allFigures()}, which never crosses the HTTP boundary to the sidecar.
 *
 * <p>Three checks, ALL must pass:
 * <ol>
 *   <li>Required-number presence — every figure in {@link Recommendation#allFigures()} that the
 *       template would have shown must appear in the output (v1.1: same numeric normalisation
 *       as check 3, digit-bounded so required "2000" is not satisfied by "12000").</li>
 *   <li>Proper-noun negative control — every capitalised, non-sentence-initial word in the
 *       output that is not a common English/domain word must be in the gazetteer
 *       ({@link Recommendation#namedEntities()}).</li>
 *   <li>Positive control — every number extracted from the output (normalised the same way)
 *       must be a member of {@link Recommendation#allFigures()}; a number absent from that set
 *       (fabricated or altered) fails the check even if all required numbers are also present.</li>
 * </ol>
 */
public final class HallucinationValidator {

    // Common English function words + the domain's COMMON nouns (capitalised forms are NOT
    // proper-noun names) + weekday/month names + Genova place names. Mirrors
    // llm_sidecar/validator.py's _COMMON_WORDS exactly (task 13 parity).
    private static final Set<String> COMMON_WORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "if", "then", "for", "to", "of", "in",
            "on", "at", "by", "with", "from", "as", "is", "are", "was", "were", "be",
            "it", "its", "this", "that", "these", "those", "your", "you", "our", "we",
            "i", "he", "she", "they", "his", "her", "their", "no", "not", "yes", "so",
            // "may" (modal verb) deliberately omitted here — it reappears below as a month name,
            // and Set.of rejects duplicate elements (unlike Python's frozenset, which the
            // COMMON_WORDS list otherwise mirrors verbatim).
            "because", "due", "should", "will", "would", "can", "now", "up", "down",
            "berth", "berths", "tug", "tugs", "vessel", "vessels", "ship", "ships",
            "tanker", "tankers", "container", "containers", "cargo", "ferry", "ferries",
            "cruise", "port", "harbour", "harbor", "master", "customs", "weather", "storm",
            "escort", "pilot", "priority", "market", "deal", "deals", "offer", "counteroffer",
            "hour", "hours", "minute", "minutes", "euro", "euros", "recommend", "recommended",
            "reasoning", "accept", "reject", "counter", "hold", "expedite", "clear",
            "clearance", "safe", "average", "value", "expected", "acceptance", "probability",
            "action", "proposed", "based", "recent", "hazmat", "class", "liquid", "bulk",
            "general", "containerized", "arrival", "arrivals", "eta", "reputation",
            "genova", "genoa", "italy", "italian", "ligurian", "mediterranean", "eur",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "january", "february", "march", "april", "may", "june", "july", "august",
            "september", "october", "november", "december");

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z_]*");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[.!?]+");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s_-]+");
    // Thousand separators (comma / apostrophe / whitespace) ONLY between two digits, so "5 hours"
    // and "berth 3" are left untouched — mirrors validator.py's _THOUSANDS_RE.
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d)[,'\\s](?=\\d)");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");

    private HallucinationValidator() {
    }

    public static boolean validate(String llmOutput, Recommendation rec) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return false;
        }
        Set<String> allowedFigures = rec.allFigures();
        String normalisedOutput = stripNumFormatting(llmOutput);

        if (!requiredNumbersPresent(normalisedOutput, allowedFigures)) {
            return false;
        }
        Set<String> gazetteer = buildGazetteer(rec.namedEntities());
        if (!properNounsKnown(llmOutput, gazetteer)) {
            return false;
        }
        return !containsFabricatedNumber(normalisedOutput, allowedFigures);
    }

    private static boolean requiredNumbersPresent(String normalisedOutput, Set<String> requiredFigures) {
        for (String figure : requiredFigures) {
            String needle = stripNumFormatting(figure);
            if (needle.isEmpty()) {
                continue;
            }
            Pattern bounded = Pattern.compile("(?<!\\d)" + Pattern.quote(needle) + "(?!\\d)");
            if (!bounded.matcher(normalisedOutput).find()) {
                return false;
            }
        }
        return true;
    }

    private static boolean properNounsKnown(String llmOutput, Set<String> gazetteer) {
        for (String properNoun : properNouns(llmOutput)) {
            String key = normaliseEntity(properNoun);
            if (COMMON_WORDS.contains(key)) {
                continue;
            }
            if (!gazetteer.contains(key)) {
                return false;
            }
        }
        return true;
    }

    /** True iff the output contains a number that is NOT one of {@code allowedFigures}. */
    private static boolean containsFabricatedNumber(String normalisedOutput, Set<String> allowedFigures) {
        Matcher m = DIGIT_RUN.matcher(normalisedOutput);
        while (m.find()) {
            if (!allowedFigures.contains(m.group())) {
                return true;
            }
        }
        return false;
    }

    /** Capitalised, non-sentence-initial word tokens from the output (planning/10 step 4). */
    private static List<String> properNouns(String text) {
        List<String> out = new ArrayList<>();
        for (String sentence : SENTENCE_SPLIT.split(text)) {
            Matcher m = WORD.matcher(sentence);
            int index = 0;
            while (m.find()) {
                if (index > 0 && Character.isUpperCase(m.group().charAt(0))) {
                    out.add(m.group());
                }
                index++;
            }
        }
        return out;
    }

    private static Set<String> buildGazetteer(Set<String> entities) {
        Set<String> gazetteer = new LinkedHashSet<>();
        for (String entity : entities) {
            if (entity == null || entity.isBlank()) {
                continue;
            }
            gazetteer.add(normaliseEntity(entity));
            // Index each word too so multi-word names ("MV Aurora") match token-wise.
            for (String word : SEPARATORS.split(entity.strip())) {
                if (!word.isEmpty()) {
                    gazetteer.add(normaliseEntity(word));
                }
            }
        }
        return gazetteer;
    }

    /** "Berth_3" / "Berth 3" / "berth3" all -> "berth3"; "Aurora" -> "aurora". */
    private static String normaliseEntity(String token) {
        return SEPARATORS.matcher(token.strip().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** "€2,000" -> "2000", "2 000" -> "2000", "85%" -> "85". */
    private static String stripNumFormatting(String s) {
        String noCurrency = s.replace("€", "").replace("$", "").replace("£", "").replace("%", "");
        return THOUSANDS.matcher(noCurrency).replaceAll("");
    }
}
