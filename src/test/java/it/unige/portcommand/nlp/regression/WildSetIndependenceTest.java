package it.unige.portcommand.nlp.regression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.testsupport.Goldens;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wild set's ONE structural claim: its utterances came from somewhere other than the corpora
 * it measures.
 *
 * <p>The wild-set numbers are the honesty measure both course reports cite, so "these sentences are
 * out of the developer's distribution" has to be checked, not asserted in prose. The first draft of
 * `wild_set.jsonl` failed this: an adversarial review found 19 rows that were verbatim copies of, or
 * one filler token away from, a `dcg_goldens.jsonl` line, a `test_dcg.pl` case or an
 * `nlu_authoring.csv` row — and every single utterance the evaluation scored as CORRECT was one of
 * them, so the run demonstrated exactly zero generalisation while reading as a 57% result.
 *
 * <p>This is a plain unit test (no service, no container) applying the same ≤1-token rule
 * {@code csv_to_nlu.py} enforces on the Rasa holdout. It cannot prove the set is genuinely
 * independent — an LLM that has read the repository writes repo-flavoured English no matter what,
 * which is why {@code docs/testing.md} still calls these figures an upper bound — but it does make
 * the strongest form of contamination impossible to reintroduce silently.
 */
class WildSetIndependenceTest {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private static String normalise(String s) {
        return NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** NOT {@code Set.of(...)}: it throws on a duplicate element, and an ordinary sentence repeats
     * words ("the berth for the tanker"). Same trap as the {@code COMMON_WORDS} crash in task 13. */
    private static Set<String> tokens(String s) {
        String n = normalise(s);
        return n.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(n.split(" +")));
    }

    /** Every utterance the developer wrote for the system under test: the DCG goldens, the intent
     * goldens, and the whole Rasa authoring corpus (both splits). */
    private static List<String> developerCorpus() {
        List<String> out = new ArrayList<>();
        Goldens.load("/nlp/golden/dcg_goldens.jsonl").forEach(g -> out.add(g.get("utterance").asText()));
        Goldens.load("/nlp/golden/intent_goldens.jsonl").forEach(g -> out.add(g.get("utterance").asText()));
        out.addAll(rasaCorpus());
        return out;
    }

    /** The Rasa corpus alone (both splits) — the intent goldens' disjointness reference. */
    private static List<String> rasaCorpus() {
        // The authoring CSV is the Rasa corpus's source of truth and lives outside the Java module.
        Path csv = Path.of("..", "nlp-python", "rasa", "data", "nlu_authoring.csv");
        assertTrue(Files.exists(csv), () -> "authoring corpus not found at " + csv.toAbsolutePath()
                + " — fix this path rather than letting the independence check pass vacuously");
        List<String> out = new ArrayList<>();
        try (var lines = Files.lines(csv, StandardCharsets.UTF_8)) {
            lines.skip(1).forEach(line -> {
                List<String> cells = splitCsvRow(line);
                if (cells.size() >= 2) {
                    out.add(cells.get(1));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * RFC-4180-aware field split of one authoring row ({@code intent,text,annotated_text,split}).
     *
     * <p><b>Audit D-07 (2026-07-27).</b> This used to be {@code line.split(",")} plus {@code cells[1]},
     * on the written assumption that "`text` never contains a comma in this corpus". <b>30 of the
     * 580 rows do</b>, and they are properly RFC-4180-quoted, so the naive split handed the guard a
     * truncated, quote-prefixed fragment as its reference string. For example row 3's
     * {@code "Offering 1800 for the cargo, 6 hours"} became {@code "Offering 1800 for the cargo} —
     * five tokens instead of seven. A wild-set author echoing that sentence (exactly the shape a
     * multi-slot utterance invites) would score a symmetric difference of 2 and a length difference
     * of 2 against the fragment, so the &le;1-token rule never fired and the contaminated row
     * passed. Thirty of the developer's most distinctive utterances were unprotected by the ONE
     * guard that makes the honesty number both course reports cite non-vacuous.
     */
    private static List<String> splitCsvRow(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"'); // RFC-4180 escaped quote inside a quoted field
                    i++;
                } else {
                    inQuotes = false;
                }
            } else if (c == '"' && cell.isEmpty()) {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    /**
     * Audit D-07's loud-failure half: the reference corpus must never contain a fragment with a
     * dangling quote, and the quote-aware path must actually be exercised. Without the second
     * assertion this guard could silently go back to being vacuous if the corpus were ever
     * regenerated without quoted fields.
     */
    @Test
    void theAuthoringCorpusIsReadWithItsQuotedFieldsIntact() {
        List<String> corpus = rasaCorpus();
        assertEquals(580, corpus.size(), "every authoring row must yield a text column");

        List<String> truncated = corpus.stream().filter(s -> s.startsWith("\"") || s.endsWith("\"")).toList();
        assertEquals(List.of(), truncated,
                "a reference string with a dangling quote is a naive-split fragment, not an utterance");

        long withCommas = corpus.stream().filter(s -> s.contains(",")).count();
        assertTrue(withCommas > 0,
                "no corpus row contains a comma — the quote-aware reader is untested, and the "
                        + "≤1-token guard below would be comparing against whatever split(\",\") returns");
    }

    /**
     * The intent goldens' own disjointness claim, checked rather than asserted in prose.
     *
     * <p>{@code RasaRegressionIT}'s javadoc and {@code docs/testing.md} both say the anchors are
     * "verified disjoint from both splits (exact and &le;1-token near-duplicate scan)" — but until
     * task 26's adversarial review nothing enforced it, and the five {@code out_of_scope} anchors
     * added in that task went in with only a hand check. An anchor that has leaked into training
     * stops being a regression detector and becomes a memorisation test that always passes.
     *
     * <p>Same &le;1-token rule {@code csv_to_nlu.py} applies to the holdout, and the same rule the
     * wild-set test above applies — one implementation, three corpora.
     */
    @Test
    void noIntentGoldenIsATrainingUtteranceOrOneTokenFromOne() {
        List<JsonNode> goldens = Goldens.load("/nlp/golden/intent_goldens.jsonl");
        assertEquals(50, goldens.size(), "5 anchors per canonical intent, 10 intents since task 26");
        List<String> corpus = rasaCorpus();
        assertTrue(corpus.size() > 500,
                () -> "the Rasa corpus loaded only " + corpus.size() + " rows — the comparison "
                        + "would pass vacuously");

        Set<String> corpusNormalised = corpus.stream().map(WildSetIndependenceTest::normalise)
                .collect(Collectors.toCollection(HashSet::new));
        List<Set<String>> corpusTokens = corpus.stream().map(WildSetIndependenceTest::tokens).toList();

        List<String> offenders = new ArrayList<>();
        for (JsonNode g : goldens) {
            String utterance = g.get("utterance").asText();
            Set<String> t = tokens(utterance);
            if (t.isEmpty()) {
                continue;
            }
            if (corpusNormalised.contains(normalise(utterance))) {
                offenders.add("VERBATIM in training: " + utterance);
                continue;
            }
            for (int i = 0; i < corpusTokens.size(); i++) {
                Set<String> c = corpusTokens.get(i);
                if (c.isEmpty()) {
                    continue;
                }
                Set<String> symmetric = new HashSet<>(t);
                c.forEach(tok -> {
                    if (!symmetric.add(tok)) {
                        symmetric.remove(tok);
                    }
                });
                if (symmetric.size() <= 1 && Math.abs(t.size() - c.size()) <= 1) {
                    offenders.add("~1 token from \"" + corpus.get(i) + "\": " + utterance);
                    break;
                }
            }
        }
        assertEquals(List.of(), offenders,
                "intent goldens that overlap the Rasa corpus — rewrite them, do not relabel them");
    }

    @Test
    void noWildUtteranceIsADeveloperUtteranceOrOneTokenFromOne() {
        List<JsonNode> wild = Goldens.load("/nlp/golden/wild_set.jsonl");
        List<String> corpus = developerCorpus();
        assertTrue(corpus.size() > 500,
                () -> "the developer corpus loaded only " + corpus.size() + " utterances — the "
                        + "comparison would pass vacuously");

        Set<String> corpusNormalised = corpus.stream().map(WildSetIndependenceTest::normalise)
                .collect(Collectors.toCollection(HashSet::new));
        List<Set<String>> corpusTokens = corpus.stream().map(WildSetIndependenceTest::tokens).toList();

        List<String> offenders = new ArrayList<>();
        for (JsonNode w : wild) {
            String utterance = w.get("utterance").asText();
            Set<String> t = tokens(utterance);
            if (t.isEmpty()) {
                continue;
            }
            if (corpusNormalised.contains(normalise(utterance))) {
                offenders.add(w.get("id").asText() + " VERBATIM: " + utterance);
                continue;
            }
            for (int i = 0; i < corpusTokens.size(); i++) {
                Set<String> c = corpusTokens.get(i);
                if (c.isEmpty()) {
                    continue;
                }
                Set<String> symmetric = new HashSet<>(t);
                c.forEach(tok -> {
                    if (!symmetric.add(tok)) {
                        symmetric.remove(tok);
                    }
                });
                if (symmetric.size() <= 1 && Math.abs(t.size() - c.size()) <= 1) {
                    offenders.add(w.get("id").asText() + " ~1 token from \"" + corpus.get(i) + "\": " + utterance);
                    break;
                }
            }
        }
        assertEquals(List.of(), offenders,
                "wild-set rows that reuse developer test data — rewrite them from the task card, "
                        + "do not relabel them");
    }
}
