package it.unige.portcommand.nlp.regression;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.artifacts.MarketStats;
import it.unige.portcommand.assistant.AssistantPromptBuilder;
import it.unige.portcommand.assistant.HallucinationValidator;
import it.unige.portcommand.assistant.Recommendation;
import it.unige.portcommand.harbourmaster.financial.IncomeRules;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.nlp.LLMRequest;
import it.unige.portcommand.nlp.LLMResponse;
import it.unige.portcommand.testsupport.Goldens;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end hallucination-guard regression (planning/25 Step 25.7): 20 recommendation traces
 * pushed through the real Flask sidecar and checked with the Java-side
 * {@link HallucinationValidator}. Gate: at least 18 of 20 pass.
 *
 * <p><b>Availability.</b> Skipped (not failed) when the sidecar's {@code /health} is not ready, so
 * {@code ./gradlew check} stays green on a machine without it. Task 13b's ONNX-INT4 backend
 * ({@code LLM_QUANT=onnx}) makes a real run cheap — ~17&nbsp;s/trace, so the full 20 is a few
 * minutes; at fp16 it would be ~3&nbsp;hours, which is why this is not a per-commit gate.
 *
 * <p>{@link #everyTraceAdvertisesExactlyTheFiguresItsTextRenders()} needs no sidecar and always
 * runs: it pins {@link Recommendation#allFigures()} against the committed goldens, which is the
 * contract the whole guard rests on (that set is both the required-number list and the positive
 * control).
 */
@Tag("integration")
class LlmHallucinationIT {

    private static final String CORPUS = "/nlp/golden/llm_templates.jsonl";
    /** planning/25 Step 25.7: "assertion: validate(...) returns true for >= 18/20". */
    private static final int REQUIRED_PASSES = 18;

    /**
     * Per-trace request timeout for the MEASUREMENT, pinned through {@link LLMBridge}'s explicit
     * test/ops seam instead of the config-driven production default (30 s, sized in task 13b from
     * a ~17 s quiet-machine generation).
     *
     * <p>2026-07-27: the first re-measurement of this suite lost 12 of 20 traces to
     * {@code LLMTimeoutException} because a Rasa training run happened to share the CPU — ONNX-INT4
     * inference is CPU-bound, and generation stretched past 30 s. Those rows scored as failures in a
     * table whose whole purpose is to report how often the VALIDATOR passes, which made the
     * measurement an artefact of machine load. This suite is not a latency test — production
     * latency is governed by {@code llm.timeout_ms} in {@code defaults.json} and is unchanged — so
     * the bound here is set well past any plausible generation. A timeout at this length means the
     * sidecar is genuinely wedged, not merely busy.
     */
    private static final java.time.Duration MEASUREMENT_TIMEOUT = java.time.Duration.ofSeconds(120);

    private static List<JsonNode> corpus;

    @BeforeAll
    static void loadCorpus() {
        corpus = Goldens.load(CORPUS);
        assertEquals(20, corpus.size(), "planning/25 pins 20 LLM trace templates");
    }

    /** Rebuilds the {@link Recommendation} a golden line describes — the same shape
     * {@code RecommendationAlgorithm.run} produces. */
    private static Recommendation toRecommendation(JsonNode g) {
        JsonNode m = g.get("market");
        JsonNode band = g.get("fallback_band");
        boolean lowConfidence = band != null && !band.isNull();
        MarketStats stats = new MarketStats(m.get("mean").asDouble(), m.get("stddev").asDouble(),
                m.get("sample_count").asInt(), 0.0, 0.0, 0.0, lowConfidence);
        IncomeRules.PriceRange fallback = lowConfidence
                ? new IncomeRules.PriceRange(band.get("lo").asDouble(), band.get("hi").asDouble())
                : null;
        return new Recommendation(g.get("action").asText(), g.get("price").asDouble(),
                g.get("p_accept").asDouble(), g.get("ev").asDouble(), List.of("R7"), stats,
                g.get("vessel_type").asText(), g.get("berth_id").asText(),
                g.get("duration_hours").asInt(), g.get("prolog_status").asText(), fallback);
    }

    private static Set<String> expectedFigures(JsonNode g) {
        Set<String> out = new LinkedHashSet<>();
        g.get("expected_numbers").forEach(n -> out.add(n.asText()));
        return out;
    }

    /**
     * The figure contract, checked without the sidecar. If {@code allFigures()} ever emits a number
     * the prompt does not render (or stops emitting one it does), every explanation silently fails
     * check 1 and the Assistant degrades to its template with no visible symptom — the exact
     * failure the empty-market Hint fix had to avoid. Committing the expected set makes that a
     * one-line diff instead of an invisible behaviour change.
     */
    @Test
    void everyTraceAdvertisesExactlyTheFiguresItsTextRenders() {
        for (JsonNode g : corpus) {
            Recommendation rec = toRecommendation(g);
            assertEquals(expectedFigures(g), rec.allFigures(),
                    () -> "allFigures() drifted for trace " + g.get("id").asText());
            // ...and the prompt the LLM will see must itself satisfy the guard, or no answer can.
            assertTrue(HallucinationValidator.validate(AssistantPromptBuilder.prompt(rec).user(), rec),
                    () -> "the prompt for " + g.get("id").asText() + " does not survive its own validator");
        }
    }

    @Test
    void theValidatorAcceptsAtLeast18Of20RealGenerations() throws Exception {
        LLMBridge bridge = new LLMBridge(LLMBridge.resolveExplainUri(), LLMBridge.resolveHealthUri(),
                httpClient(), new ObjectMapper(), MEASUREMENT_TIMEOUT);
        Assumptions.assumeTrue(bridge.isReady(),
                "LLM sidecar not ready on " + LLMBridge.resolveHealthUri()
                        + " — start nlp-python/start_llm.bat with LLM_QUANT=onnx (see docs/testing.md)");

        int passes = 0;
        List<String> rows = new ArrayList<>();
        for (JsonNode g : corpus) {
            String id = g.get("id").asText();
            Recommendation rec = toRecommendation(g);
            AssistantPromptBuilder.PromptPayload prompt = AssistantPromptBuilder.prompt(rec);
            // Exactly the production request shape (RecommendOnDemandBehaviour / ExplainEventBehaviour).
            LLMRequest request = new LLMRequest(prompt.user(), prompt.system(),
                    rec.requiredFigures().stream().toList(), rec.namedEntities().stream().toList(), true);

            String verdict;
            try {
                LLMResponse response = bridge.explain(request).get();
                boolean ok = HallucinationValidator.validate(response.text(), rec);
                if (ok) {
                    passes++;
                }
                verdict = (ok ? "PASS" : "FAIL") + " | sidecar=" + response.validated()
                        + " | " + oneLine(response.text());
            } catch (ExecutionException e) {
                verdict = "ERROR | " + e.getCause();
            }
            rows.add("| " + id + " | " + verdict + " |");
        }

        String report = "# LLM hallucination-guard regression (task 25, 20 traces)\n\n"
                + passes + "/" + corpus.size() + " passed the Java-side validator (gate: >= "
                + REQUIRED_PASSES + ").\n\n| trace | verdict / sidecar verdict / text |\n|---|---|\n"
                + String.join("\n", rows) + "\n";
        Path written = Goldens.writeReport("nlp-llm", "hallucination.md", report);
        System.out.println(report);
        System.out.println("LLM regression table written to " + written.toAbsolutePath());

        int finalPasses = passes;
        assertTrue(passes >= REQUIRED_PASSES,
                () -> "hallucination validator passed only " + finalPasses + "/" + corpus.size()
                        + " (need " + REQUIRED_PASSES + "); see " + written + ".\n"
                        + "MEASUREMENT HISTORY (Phi-4-mini-instruct, ONNX-INT4, max_new_tokens=120, "
                        + "greedy). 2026-07-27 task 25: 2/20 — faithful generations, but check 1 "
                        + "demanded EVERY figure in allFigures() and the model dropped the least "
                        + "salient one (the sample count in 18/18 failures). 2026-07-27 task 26 "
                        + "decision a, step 1 (name the dropped figures in the system prompt): 15/20. "
                        + "Step 2 (check 1 narrowed to price / p_accept / ev; check 3 still spans "
                        + "every figure): 18/20 — exactly the gate. The two that still fail are REAL: "
                        + "asked for a 0% acceptance probability the model writes '100% likelihood of "
                        + "rejection', and 100 is a number the recommendation never contained. That is "
                        + "the positive control doing its job; do not weaken it, and do not lower this "
                        + "threshold to make the build green (planning/25 R13, planning/26 R-a).\n"
                        + "If this is red, check FIRST whether the sidecar is on the ONNX backend "
                        + "(LLM_QUANT=onnx) and whether anything else is loading the CPU — a busy "
                        + "machine used to turn these rows into timeouts.");
    }

    private static java.net.http.HttpClient httpClient() {
        return java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
    }

    private static String oneLine(String text) {
        return text == null ? "(null)" : text.replaceAll("\\s+", " ").trim();
    }
}
