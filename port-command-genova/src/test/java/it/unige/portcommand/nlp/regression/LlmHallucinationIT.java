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
                httpClient(), new ObjectMapper());
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
                    rec.allFigures().stream().toList(), rec.namedEntities().stream().toList(), true);

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
                        + "KNOWN FAILURE, measured 2/20 on 2026-07-27 (Phi-4-mini-instruct, ONNX-INT4, "
                        + "max_new_tokens=120). The generations are FAITHFUL — they invent nothing, so "
                        + "check 3 (positive control) holds on all 20 — but check 1 requires EVERY figure "
                        + "in allFigures() to appear, and the model omits one. Measured per trace: the "
                        + "SAMPLE COUNT is missing in 18/18 failures; stddev, mean, ev and duration are "
                        + "each missing in exactly 1. Output is not truncated (~47 words of a 120-token "
                        + "budget) — the model is choosing to drop the least salient figure. The fix is a "
                        + "task-10 design decision, not this suite's: dropping the sample count alone "
                        + "reaches exactly 18/20, and requiring only price / p_accept / ev reaches 19/20 "
                        + "(t03 also omits the EV). Either way stddev and sample count stay in the "
                        + "positive control, so the model still may not ALTER them. Do NOT lower this "
                        + "threshold to make the build green; see planning/25 R13.");
    }

    private static java.net.http.HttpClient httpClient() {
        return java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
    }

    private static String oneLine(String text) {
        return text == null ? "(null)" : text.replaceAll("\\s+", " ").trim();
    }
}
