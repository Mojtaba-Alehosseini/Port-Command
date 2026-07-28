package it.unige.portcommand.nlp.regression;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.nlp.ConfidenceGate;
import it.unige.portcommand.nlp.DcgTokenizer;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.NLPPipeline;
import it.unige.portcommand.nlp.OntologyValidator;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.nlp.PreprocessRegex;
import it.unige.portcommand.nlp.PrologDcgParser;
import it.unige.portcommand.nlp.RasaBridge;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.testsupport.Goldens;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>The wild-set evaluation (planning/25 v1.1 addendum; PROJECT_DEFINITION §13.11).</b> Runs the
 * FULL cascade — preprocess &rarr; DCG &rarr; Rasa &rarr; confidence gate &rarr; clarification —
 * over ~130 utterances that were NOT written against the grammar or the Rasa corpus, and writes a
 * coverage / precision / end-to-end-accuracy report to {@code build/reports/nlp-wild/}.
 *
 * <p><b>REPORTED, NEVER GATED.</b> This class makes no assertion about the RESULT, by design:
 * §13.11 makes the wild set an honest measurement for the two course reports, not an acceptance
 * gate. A low score is a finding to write up, not a broken build, and every per-utterance failure
 * is caught into an outcome row rather than thrown. The only control-flow it does exercise is an
 * {@link Assumptions#assumeTrue} on the Rasa server: a run without the cascade's middle stage is
 * not a measurement of the cascade, and letting it write the report anyway would overwrite a real
 * result with an outage (which is exactly what happened before that check existed).
 *
 * <p><b>How the set was built, and its limitation.</b> The addendum's preferred source is
 * course-mates given task cards; the sanctioned fallback — used here — is LLM-generated paraphrases
 * of those task cards, hand-filtered. The generator had prior sight of this repository, so the set
 * is <i>not</i> fully independent of the system it measures: it is biased toward vocabulary the
 * system already knows, and the true out-of-distribution number is therefore <b>no better</b> than
 * what this reports. Recorded honestly in {@code docs/testing.md}; replacing these lines with real
 * course-mate sentences is a drop-in file swap and needs no code change.
 *
 * <p><b>Metrics.</b> For each utterance the golden names the ACL performative a correct system
 * should emit, or {@code clarification} when asking is the right answer.
 * <ul>
 *   <li><b>coverage</b> — routed (not clarification/error) &divide; utterances whose golden is a
 *       performative. How much of real player language the system acts on at all.</li>
 *   <li><b>precision</b> — correct performative &divide; routed. Of what it acts on, how much it
 *       gets right. This is the number that matters: a wrong ACL is worse than a clarification.</li>
 *   <li><b>clarification recall</b> — correctly asked &divide; utterances whose golden IS
 *       {@code clarification}. Knowing when to ask is a capability, not a failure.</li>
 *   <li><b>end-to-end accuracy</b> — right outcome &divide; all utterances, the two above combined.</li>
 * </ul>
 */
@Tag("integration")
class WildSetEvaluationIT {

    private static final String CORPUS = "/nlp/golden/wild_set.jsonl";
    private static final String CLARIFICATION = "clarification";

    private static ExecutorService executor;
    private static NLPPipeline pipeline;
    private static boolean rasaUp;

    @BeforeAll
    static void wireTheRealCascade() {
        PrologEngine.getInstance().init();
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "wild-set");
            t.setDaemon(true);
            return t;
        });
        RasaBridge rasa = new RasaBridge(RasaBridge.resolveParseUri(),
                RasaBridge.newClientBuilder().build(), new ObjectMapper());
        // Exactly JadeBootstrap's wiring — same preprocessor, same gate, same ontology validator.
        pipeline = new NLPPipeline(new PreprocessRegex(), rasa, new ConfidenceGate(),
                new PrologDcgParser(PrologEngine.getInstance(), new DcgTokenizer()),
                new OntologyValidator(), executor);
        try {
            rasa.parse("deal");
            rasaUp = true;
        } catch (RuntimeException e) {
            rasaUp = false;
        }
    }

    @AfterAll
    static void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** The focused-dialogue fixture the {@code ctx: "focus"} rows resolve against. */
    private static final DialogueCtx FOCUS = new DialogueCtx(
            "C001",
            new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(2000.0, 5, "berth_3"),
                    new DialogueCtx.OfferView(1500.0, 5, "berth_3")),
            List.of(new DialogueCtx.RosterEntry("C001", "cargo_vessel", "Genoa Star", "berth_3", 5),
                    new DialogueCtx.RosterEntry("T001", "tanker", "Carthago", "berth_2", 8)),
            "berth_3");

    @Test
    void measuresTheCascadeOverUtterancesTheDeveloperDidNotAuthor() {
        // Skip rather than measure a cascade that is missing its middle stage. Without this the
        // class happily produced a "coverage 9.5% / end-to-end 8.5%" table in which 118 of 129 rows
        // read `error:rasa connection failure` — and, because it always writes the same filename,
        // every service-less `./gradlew check` OVERWROTE the real measurement with that one. The
        // headline read like a capability result; it was an outage. Reported-never-gated means the
        // build must not go red, not that a meaningless run should masquerade as evidence.
        Assumptions.assumeTrue(rasaUp,
                "Rasa NLU server not answering on " + RasaBridge.resolveParseUri() + " — the wild-set "
                        + "evaluation measures the FULL cascade, and a DCG-only run is not that. Start "
                        + "it with nlp-python/start_rasa.bat (see docs/testing.md).");

        List<JsonNode> corpus = Goldens.load(CORPUS);

        List<String[]> rows = new ArrayList<>();
        List<String> falseBinds = new ArrayList<>();
        Map<String, int[]> perCard = new LinkedHashMap<>();
        int routed = 0;
        int routedCorrect = 0;
        int wantedRouting = 0;
        int wantedClarification = 0;
        int clarifiedCorrectly = 0;

        for (JsonNode g : corpus) {
            String id = g.get("id").asText();
            String card = g.get("card").asText();
            String utterance = g.get("utterance").asText();
            String expect = g.get("expect").asText();
            DialogueCtx ctx = "focus".equals(g.get("ctx").asText()) ? FOCUS : DialogueCtx.NONE;

            String actual = outcomeOf(utterance, ctx);
            boolean correct = expect.equals(actual);

            if (CLARIFICATION.equals(expect)) {
                wantedClarification++;
                if (correct) {
                    clarifiedCorrectly++;
                } else if (!actual.startsWith("error")) {
                    // A turn that should have ASKED but emitted a binding ACL instead. This is the
                    // worst outcome in the whole table and the precision metric cannot see it — its
                    // denominator is should-route rows only — so it is counted separately.
                    falseBinds.add(utterance + " -> " + actual);
                }
            } else {
                wantedRouting++;
                if (!CLARIFICATION.equals(actual) && !actual.startsWith("error")) {
                    routed++;
                    if (correct) {
                        routedCorrect++;
                    }
                }
            }
            int[] cardTally = perCard.computeIfAbsent(card, k -> new int[2]);
            cardTally[1]++;
            if (correct) {
                cardTally[0]++;
            }
            rows.add(new String[]{id, card, utterance, expect, actual, correct ? "ok" : "MISS"});
        }

        int total = corpus.size();
        int correctOverall = clarifiedCorrectly + routedCorrect;
        String report = render(total, wantedRouting, wantedClarification, routed, routedCorrect,
                clarifiedCorrectly, correctOverall, perCard, rows, falseBinds);
        Path written = Goldens.writeReport("nlp-wild", "wild-set.md", report);
        System.out.println(report);
        System.out.println("Wild-set report written to " + written.toAbsolutePath());
    }

    /** The observable outcome of one chat turn, as a comparable label. Never throws. */
    private static String outcomeOf(String utterance, DialogueCtx ctx) {
        try {
            PipelineResult result = pipeline.processChatInput(utterance, ctx).get(30, TimeUnit.SECONDS);
            if (result instanceof PipelineResult.Routed r) {
                // JADE spells the FIPA names with hyphens ("ACCEPT-PROPOSAL"); the goldens use the
                // underscore spelling of the ACLMessage constants (ACCEPT_PROPOSAL), which is how
                // the rest of this codebase refers to them. Normalise here so the two agree.
                return ACLMessage.getPerformative(r.msg().getPerformative()).replace('-', '_');
            }
            if (result instanceof PipelineResult.NeedsClarification) {
                return CLARIFICATION;
            }
            return "error:" + ((PipelineResult.Error) result).reason();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "error:interrupted";
        } catch (Exception e) {
            // Reported, never gated: an infrastructure failure becomes a row, not a red build.
            return "error:" + e.getClass().getSimpleName();
        }
    }

    private static String render(int total, int wantedRouting, int wantedClarification, int routed,
                                 int routedCorrect, int clarifiedCorrectly, int correctOverall,
                                 Map<String, int[]> perCard, List<String[]> rows,
                                 List<String> falseBinds) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Wild-set evaluation (task 25 / PROJECT_DEFINITION §13.11)\n\n");
        sb.append("Full cascade: preprocess -> DCG -> Rasa -> confidence gate -> clarification.\n");
        sb.append("Rasa NLU server: ").append(rasaUp ? "UP" : "**DOWN — DCG-only run, coverage is a floor**")
                .append(".\n\n");
        sb.append("Utterances are LLM paraphrases of player task cards, hand-filtered; the generator "
                + "had prior sight of the repository, so these figures are an UPPER bound on true "
                + "out-of-distribution performance (see docs/testing.md).\n\n");

        sb.append("## Headline\n\n| metric | value | of |\n|---|---|---|\n");
        sb.append(row("coverage", routed, wantedRouting, "utterances that should route"));
        sb.append(row("precision", routedCorrect, routed, "utterances actually routed"));
        sb.append(row("clarification recall", clarifiedCorrectly, wantedClarification,
                "utterances that should ask"));
        sb.append(row("end-to-end accuracy", correctOverall, total, "all utterances"));
        sb.append(row("**false-bind rate**", falseBinds.size(), wantedClarification,
                "utterances that should ask — **emitted a binding ACL instead**"));
        if (!falseBinds.isEmpty()) {
            sb.append("\nFalse binds (the worst failure mode — precision cannot see these):\n\n");
            falseBinds.forEach(f -> sb.append("- ").append(f).append('\n'));
        }

        sb.append("\n## Per task card\n\n| card | correct | total | accuracy |\n|---|---|---|---|\n");
        perCard.forEach((card, t) -> sb.append(String.format("| %s | %d | %d | %s |%n",
                card, t[0], t[1], pct(t[0], t[1]))));

        sb.append("\n## Every utterance\n\n| id | card | utterance | expected | actual | |\n|---|---|---|---|---|---|\n");
        for (String[] r : rows) {
            sb.append(String.format("| %s | %s | %s | %s | %s | %s |%n",
                    r[0], r[1], r[2].replace('|', '/'), r[3], r[4], r[5]));
        }
        return sb.toString();
    }

    private static String row(String metric, int num, int den, String of) {
        return String.format("| %s | %s (%d/%d) | %s |%n", metric, pct(num, den), num, den, of);
    }

    private static String pct(int num, int den) {
        return den == 0 ? "n/a" : String.format("%.1f%%", 100.0 * num / den);
    }
}
