package it.unige.portcommand.nlp.regression;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.nlp.RasaBridge;
import it.unige.portcommand.nlp.RasaParseResult;
import it.unige.portcommand.testsupport.Goldens;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NLU regression against the TRAINED Rasa model (task 12's {@code portcmd_nlu.tar.gz}), over the
 * 50 hand-authored goldens in {@code intent_goldens.jsonl} — 5 per canonical intent.
 *
 * <p><b>2026-07-27 (task 26, decision b): 45 &rarr; 50.</b> The canonical intent set went 9
 * &rarr; 10 ({@code out_of_scope}), so the anchor gained its 5 rows like every other intent. The
 * pre-existing 45 are untouched and still expect the same intents — the retrain must not move them.
 *
 * <p><b>Not the acceptance gate.</b> The F1 ≥ 0.85 gate is `rasa test nlu` on the 96-row holdout
 * ({@code nlp-python/test_rasa.sh}); that is the number the report cites. This suite is the
 * REGRESSION anchor: 50 utterances that appear in neither the training nor the holdout split
 * (verified disjoint, including a &le;1-token near-duplicate scan), so a pipeline change,
 * a config edit, or an accidental retrain on different data shows up here as a diff rather than
 * as a silent drift nobody notices until the demo.
 *
 * <p><b>Soft-failure budget.</b> planning/25 Step 25.5 allows 10% soft failures — the goldens are
 * deliberately a little harder than the holdout, and a single stubborn paraphrase should flag,
 * not redden. The build fails only above {@code floor(0.10 * n)} mismatches. Every mismatch is
 * printed and written to {@code build/reports/nlp-rasa/} whether or not the budget is blown.
 *
 * <p><b>Availability.</b> Skipped (not failed) when nothing answers on port 5005, so
 * {@code ./gradlew check} — and hence the pre-commit hook — stays green on a machine that has not
 * started the NLU server. See {@code docs/testing.md}: the release checklist runs it with the
 * server up and records the number.
 */
@Tag("integration")
class RasaRegressionIT {

    private static final String CORPUS = "/nlp/golden/intent_goldens.jsonl";
    /** planning/25 Step 25.5: "Allowed soft failures: <= 3 out of 30 (10%)". */
    private static final double SOFT_FAILURE_BUDGET = 0.10;

    private static RasaBridge rasa;

    @BeforeAll
    static void prewarmOrSkip() {
        rasa = new RasaBridge(RasaBridge.resolveParseUri(),
                RasaBridge.newClientBuilder().build(), new ObjectMapper());
        // Doubles as the planning/25 "pre-warm" step: the first parse pays the model's cold-start
        // cost, so the timed goldens below are not measuring a one-off warm-up.
        boolean up;
        try {
            rasa.parse("deal");
            up = true;
        } catch (RuntimeException e) {
            up = false;
        }
        Assumptions.assumeTrue(up,
                "Rasa NLU server not answering on " + RasaBridge.resolveParseUri()
                        + " — start it with nlp-python/start_rasa.bat (see docs/testing.md)");
    }

    @Test
    void everyGoldenUtteranceKeepsItsIntentAndEntities() {
        List<JsonNode> corpus = Goldens.load(CORPUS);
        assertTrue(corpus.size() == 50,
                "planning/25 v1.1 addendum pins 5 intent goldens per canonical intent — 10 intents "
                        + "since task 26 decision b, so 50; found " + corpus.size());

        List<String> mismatches = new ArrayList<>();
        List<String> extraEntities = new ArrayList<>();
        Map<String, int[]> perIntent = new TreeMap<>();

        for (JsonNode g : corpus) {
            String utterance = g.get("utterance").asText();
            String expectedIntent = g.get("intent").asText();
            Map<String, Object> expectedEntities = Goldens.toElementMap(g.get("entities"));

            int[] tally = perIntent.computeIfAbsent(expectedIntent, k -> new int[2]);
            tally[1]++;

            RasaParseResult r = rasa.parse(utterance);
            List<String> problems = new ArrayList<>();
            if (!expectedIntent.equals(r.intentName())) {
                problems.add("intent " + r.intentName() + " != " + expectedIntent);
            }
            Map<String, String> got = new LinkedHashMap<>();
            r.entities().forEach((name, hit) -> got.put(name, hit.value()));
            expectedEntities.forEach((name, value) -> {
                String actual = got.get(name);
                if (actual == null) {
                    problems.add("missing entity " + name + "=" + value);
                } else if (!String.valueOf(value).equalsIgnoreCase(actual)) {
                    problems.add("entity " + name + "=" + actual + " != " + value);
                }
            });
            // An entity the golden does not list is REPORTED, never a failure: over-extraction is
            // a quality signal worth watching, but DIET adding a plausible extra span is not a
            // regression the build should die on.
            got.keySet().stream().filter(k -> !expectedEntities.containsKey(k))
                    .forEach(k -> extraEntities.add(utterance + " -> extra " + k + "=" + got.get(k)));

            if (problems.isEmpty()) {
                tally[0]++;
            } else {
                mismatches.add(String.format("[%s] %s :: %s", expectedIntent, utterance, String.join("; ", problems)));
            }
        }

        int budget = (int) Math.floor(SOFT_FAILURE_BUDGET * corpus.size());
        String report = renderReport(corpus.size(), perIntent, mismatches, extraEntities, budget);
        Path written = Goldens.writeReport("nlp-rasa", "intent-goldens.md", report);
        System.out.println(report);
        System.out.println("Rasa regression table written to " + written.toAbsolutePath());

        assertTrue(mismatches.size() <= budget,
                () -> "Rasa regression exceeded the " + budget + "-mismatch soft budget ("
                        + mismatches.size() + "/" + corpus.size() + "):\n  " + String.join("\n  ", mismatches));
    }

    private static String renderReport(int total, Map<String, int[]> perIntent,
                                       List<String> mismatches, List<String> extras, int budget) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Rasa intent-golden regression (task 25, " + total
                + " utterances disjoint from train + holdout)\n\n");
        sb.append("| intent | passed | total |\n|---|---|---|\n");
        perIntent.forEach((intent, t) -> sb.append(String.format("| %s | %d | %d |%n", intent, t[0], t[1])));
        sb.append(String.format("%n**%d/%d exact** (intent + every expected entity value); soft budget %d.%n",
                total - mismatches.size(), total, budget));
        if (!mismatches.isEmpty()) {
            sb.append("\n## Mismatches (flagged)\n\n");
            mismatches.forEach(m -> sb.append("- ").append(m).append('\n'));
        }
        if (!extras.isEmpty()) {
            sb.append("\n## Over-extraction (reported, not gated)\n\n");
            extras.forEach(m -> sb.append("- ").append(m).append('\n'));
        }
        return sb.toString();
    }
}
