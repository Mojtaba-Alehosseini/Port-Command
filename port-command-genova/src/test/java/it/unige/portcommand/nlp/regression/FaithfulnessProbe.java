package it.unige.portcommand.nlp.regression;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.assistant.AssistantPromptBuilder;
import it.unige.portcommand.assistant.HallucinationValidator;
import it.unige.portcommand.assistant.Recommendation;
import it.unige.portcommand.assistant.RecommendationAlgorithm;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.nlp.LLMRequest;
import it.unige.portcommand.nlp.LLMResponse;
import it.unige.portcommand.prolog.PrologEngine;

/**
 * S19 (playtest 5, PT41): the explanation-faithfulness number the proposal promises - REAL
 * decision traces through the Assistant's shipped Hint path, as {@code RecommendOnDemandBehaviour}
 * runs it: {@link RecommendationAlgorithm} → {@link AssistantPromptBuilder} → the LLM sidecar
 * (Phi-4-mini) → {@link HallucinationValidator}. An explanation that passes is what the chat shows
 * under the Hint as "Why: …"; one that fails the check, and a model error or timeout, leave the
 * Hint's template alone. The report says "N of M explanations passed the check; K fell back to the
 * template".
 *
 * <p>{@code LlmHallucinationIT} measures the validator on 20 hand-written golden traces; this one
 * measures it on {@value #TRACES}: 50 snapshots recorded from S19's calibration games - each the
 * moment a walk-in made an offer and the Assistant was asked, first offers and later rounds alike.
 * The algorithm sees an empty market history, as on a game's first day.
 *
 * <p><b>Not a test, in no lane</b> - its name ends in neither {@code Test} nor {@code IT}. It needs
 * the sidecar up ({@code nlp-python/start_llm.bat}, {@code LLM_QUANT=onnx}) and takes about 20 s a
 * trace; run it with {@code ./gradlew faithfulnessProbe}. It prints a row per trace and writes the
 * report to {@code build/reports/nlp-llm/faithfulness.md} (or the path given as the argument).
 */
public final class FaithfulnessProbe {

    static final String TRACES = "/nlp/faithfulness/decision_traces.jsonl";
    /** Well past any generation on a busy machine: a timeout here means the sidecar is wedged
     * (the same reasoning as {@code LlmHallucinationIT.MEASUREMENT_TIMEOUT}). */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private FaithfulnessProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path report = Path.of(args.length > 0 ? args[0] : "build/reports/nlp-llm/faithfulness.md");
        List<WalkInDialogueSnapshot> traces = load();
        PrologEngine.getInstance().init();
        RecommendationAlgorithm algorithm = new RecommendationAlgorithm(new MarketHistoryArtifact());
        LLMBridge bridge = new LLMBridge(LLMBridge.resolveExplainUri(), LLMBridge.resolveHealthUri(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), new ObjectMapper(), TIMEOUT);
        if (!bridge.isReady()) {
            System.err.println("LLM sidecar not ready on " + LLMBridge.resolveHealthUri()
                    + " - start nlp-python/start_llm.bat with LLM_QUANT=onnx");
            System.exit(2);
        }

        int passed = 0;
        int failedCheck = 0;
        int errors = 0;
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < traces.size(); i++) {
            WalkInDialogueSnapshot snapshot = traces.get(i);
            Recommendation rec = algorithm.run(snapshot);
            AssistantPromptBuilder.PromptPayload prompt = AssistantPromptBuilder.prompt(rec);
            // Exactly the production request shape (RecommendOnDemandBehaviour.onHintButton).
            LLMRequest request = new LLMRequest(prompt.user(), prompt.system(),
                    rec.requiredFigures().stream().toList(), rec.namedEntities().stream().toList(), true);
            long started = System.nanoTime();
            String verdict;
            try {
                CompletableFuture<LLMResponse> answer = bridge.explain(request);
                if (answer == null) {
                    errors++;
                    verdict = "ERROR (template) | no model call was made";
                } else {
                    LLMResponse response = answer.get();
                    boolean ok = HallucinationValidator.validate(response.text(), rec);
                    if (ok) {
                        passed++;
                    } else {
                        failedCheck++;
                    }
                    verdict = (ok ? "PASS" : "FAIL (template)") + " | " + oneLine(response.text());
                }
            } catch (ExecutionException e) {
                errors++;
                verdict = "ERROR (template) | " + e.getCause();
            }
            long millis = (System.nanoTime() - started) / 1_000_000L;
            String row = String.format(Locale.ROOT, "| %d | %s €%.0f %dh round %d | %s €%.0f | %d | %s |", i + 1,
                    snapshot.vesselType(), snapshot.lastVesselOffer(), snapshot.durationHours(),
                    snapshot.roundsUsed(), rec.action(), rec.price(), millis, verdict.replace("|", "/"));
            rows.add(row);
            System.out.println(row);
        }

        String summary = passed + " of " + traces.size() + " explanations passed the check; "
                + (failedCheck + errors) + " fell back to the template (" + failedCheck + " failed the check, "
                + errors + " model errors or timeouts).";
        String text = "# Explanation faithfulness — real decision traces (S19, PT41)\n\n" + summary + "\n\n"
                + "Phi-4-mini through the LLM sidecar; `HallucinationValidator` is the check. Traces: `"
                + TRACES + "`.\n\n| # | the offer | the recommendation | ms | verdict / text |\n|---|---|---|---|---|\n"
                + String.join("\n", rows) + "\n";
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, text, StandardCharsets.UTF_8);
        System.out.println(summary);
        System.out.println("report written to " + report.toAbsolutePath());
        System.exit(0); // the bridge's HttpClient threads must not keep the JVM up
    }

    /** The recorded snapshots, one JSON object a line, in order. */
    static List<WalkInDialogueSnapshot> load() throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<WalkInDialogueSnapshot> traces = new ArrayList<>();
        try (InputStream in = FaithfulnessProbe.class.getResourceAsStream(TRACES)) {
            if (in == null) {
                throw new IOException(TRACES + " not on the classpath");
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode t = json.readTree(line);
                traces.add(new WalkInDialogueSnapshot(t.get("vesselId").asText(), t.get("vesselType").asText(),
                        t.get("draft").asDouble(), t.get("length").asDouble(), t.get("tonnage").asInt(),
                        t.get("cargoClass").asText(), t.get("berthId").asText(), t.get("durationHours").asInt(),
                        t.get("lastVesselOffer").asDouble(), t.get("lastPlayerOffer").asDouble(),
                        t.get("roundsUsed").asInt(), t.get("timeUsedSec").asDouble(),
                        t.get("threatenedWithdrawal").asBoolean(), t.get("vesselName").asText(),
                        t.get("timeLimitSec").asDouble()));
            }
        }
        return traces;
    }

    private static String oneLine(String text) {
        return text == null ? "(null)" : text.replaceAll("\\s+", " ").trim();
    }
}
