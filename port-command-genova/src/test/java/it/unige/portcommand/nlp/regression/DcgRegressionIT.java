package it.unige.portcommand.nlp.regression;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.nlp.DCGParser;
import it.unige.portcommand.nlp.DcgTokenizer;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.Frame;
import it.unige.portcommand.nlp.PrologDcgParser;
import it.unige.portcommand.prolog.PrologEngine;
import it.unige.portcommand.testsupport.Goldens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DCG regression corpus (task 25): every line of {@code dcg_goldens.jsonl} pushed through the
 * whole Java parse chain — raw utterance &rarr; {@link DcgTokenizer} &rarr; JPL &rarr;
 * {@code parse_move/3} &rarr; {@link Frame} — and compared for structural frame equality.
 *
 * <p><b>Why this exists next to {@code test_dcg.pl} and {@code DCGParserTest}.</b> The PLUnit
 * suite asserts the grammar over hand-written TOKEN lists; {@code DCGParserTest} is task 16's
 * acceptance evidence over a curated handful of raw utterances plus the seams a corpus cannot
 * express (goal-string injection, parse ambiguity, a hostile vessel name in the context). This
 * is the ~150-line evaluation corpus organised by PHENOMENON BLOCK, and it is the only artefact
 * that produces the per-phenomenon accuracy table PROJECT_DEFINITION §13.5 (v1.1) asks the NLP
 * report to cite. The goldens are derived from task 16's block corpus and re-expressed as raw
 * English, which is what makes tokeniser and grammar prove they agree.
 *
 * <p><b>Casing is verbatim.</b> The grammar emits Prolog atoms, so frame names, move names and
 * slot keys are lowercase {@code snake_case} and the goldens carry them exactly as written
 * (planning/25 Step 25.6). The one deliberate exception is a currency, which
 * {@code Frame.fromProlog} upper-cases at the boundary ({@code eur} &rarr; {@code EUR}).
 *
 * <p><b>Element ORDER is asserted, not just membership.</b> The grammar publishes a canonical slot
 * order and {@code Frame} preserves it into the ACL wire format; a silently reordered frame is a
 * real defect, so a set-equal-but-differently-ordered frame fails here.
 */
@Tag("integration")
class DcgRegressionIT {

    private static final String CORPUS = "/nlp/golden/dcg_goldens.jsonl";

    /** The seven phenomenon blocks, in report order. A golden naming any other block fails. */
    private static final List<String> BLOCKS =
            List.of("plain", "ellipsis", "delta", "anaphora", "negation", "vocative", "command");

    private static DCGParser parser;

    /** block -> [passed, total]; filled as the dynamic tests run, printed by {@link #report()}. */
    private static final Map<String, int[]> TALLY = new LinkedHashMap<>();

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
        parser = new PrologDcgParser(PrologEngine.getInstance(), new DcgTokenizer());
        BLOCKS.forEach(b -> TALLY.put(b, new int[2]));
    }

    // --- the fixtures the `ctx` field of a golden selects ------------------------------------

    /** The focused dialogue: Genoa Star (cargo) asked 2000, the player bid 1500, berth_3 for 5 h,
     * with one tanker (Carthago) also active. Mirrors {@code test_dcg.pl}'s {@code m2_ctx/1}. */
    private static final DialogueCtx FOCUS = new DialogueCtx(
            "C001",
            new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(2000.0, 5, "berth_3"),
                    new DialogueCtx.OfferView(1500.0, 5, "berth_3")),
            List.of(new DialogueCtx.RosterEntry("C001", "cargo_vessel", "Genoa Star", "berth_3", 5),
                    new DialogueCtx.RosterEntry("T001", "tanker", "Carthago", "berth_2", 8)),
            "berth_3");

    /** Two tankers active: every "the tanker" reference is ambiguous and must refuse. */
    private static final DialogueCtx TWO_TANKERS = new DialogueCtx(
            null, DialogueCtx.StandingOffer.NONE,
            List.of(new DialogueCtx.RosterEntry("T001", "tanker", "Carthago", "berth_2", 8),
                    new DialogueCtx.RosterEntry("T002", "tanker", "Aurora", "berth_1", 6)),
            null);

    /** Only the vessel has bid: "split the difference" has no midpoint to take. */
    private static final DialogueCtx ONE_SIDED = new DialogueCtx(
            "C001",
            new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(2000.0, 5, "berth_3"),
                    DialogueCtx.OfferView.NONE),
            List.of(), null);

    private static DialogueCtx ctxNamed(String name) {
        return switch (name) {
            case "none" -> DialogueCtx.NONE;
            case "focus" -> FOCUS;
            case "two_tankers" -> TWO_TANKERS;
            case "one_sided" -> ONE_SIDED;
            default -> throw new IllegalArgumentException("unknown ctx fixture: " + name);
        };
    }

    // --- the corpus ---------------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> everyGoldenParsesToItsExpectedFrame() {
        List<JsonNode> corpus = Goldens.load(CORPUS);
        assertTrue(corpus.size() >= 140,
                "the phenomenon-block corpus is ~150 lines (PROJECT_DEFINITION §13.5 v1.1); found " + corpus.size());
        // Shape, not just size: a size-only check would still pass if every negative case or a whole
        // phenomenon block were deleted, which is exactly how an evaluation corpus quietly rots.
        long negatives = corpus.stream().filter(g -> g.get("frame") == null || g.get("frame").isNull()).count();
        assertTrue(negatives >= 40,
                "precision is measured, not assumed: the corpus must keep its refusal cases; found " + negatives);
        for (String block : BLOCKS) {
            assertTrue(corpus.stream().anyMatch(g -> block.equals(g.get("block").asText())),
                    "phenomenon block '" + block + "' has no goldens left");
        }
        return corpus.stream().map(g -> {
            String block = g.get("block").asText();
            String utterance = g.get("utterance").asText();
            String ctxName = g.get("ctx").asText();
            return DynamicTest.dynamicTest("[" + block + "/" + ctxName + "] " + utterance,
                    () -> checkGolden(block, ctxName, utterance, g));
        });
    }

    private static void checkGolden(String block, String ctxName, String utterance, JsonNode g) {
        assertTrue(TALLY.containsKey(block), () -> "unknown phenomenon block '" + block + "'");
        int[] counts = TALLY.get(block);
        counts[1]++;

        Optional<Frame> parsed = parser.parse(utterance, ctxNamed(ctxName));
        JsonNode expectedName = g.get("frame");

        if (expectedName == null || expectedName.isNull()) {
            // A negative golden: the grammar must REFUSE, so the turn falls through to Rasa or to
            // clarification. A silent bind here is the defect class the mention-guards exist for.
            assertEquals(Optional.empty(), parsed,
                    () -> "expected NO parse for [" + utterance + "] — " + note(g));
            counts[0]++;
            return;
        }

        Frame frame = parsed.orElseThrow(() -> new AssertionError("expected a parse for: " + utterance));
        assertEquals(expectedName.asText(), frame.frameName(), () -> "frame name for: " + utterance);

        Map<String, Object> expected = Goldens.toElementMap(g.get("elements"));
        assertEquals(expected, frame.elements(), () -> "frame elements for: " + utterance);
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(frame.elements().keySet()),
                () -> "slot ORDER is a published contract (Frame javadoc) for: " + utterance);
        counts[0]++;
    }

    private static String note(JsonNode g) {
        JsonNode n = g.get("note");
        return n == null ? "(no note)" : n.asText();
    }

    /**
     * Emits the per-phenomenon accuracy table. Reported for the NLP course report; the pass/fail
     * gate is the dynamic tests above, so this method never asserts.
     */
    @AfterAll
    static void report() {
        StringBuilder sb = new StringBuilder();
        sb.append("# DCG per-phenomenon accuracy (task 25 golden corpus)\n\n");
        sb.append("| block | passed | total | accuracy |\n|---|---|---|---|\n");
        int totalPassed = 0;
        int total = 0;
        for (Map.Entry<String, int[]> e : TALLY.entrySet()) {
            int passed = e.getValue()[0];
            int seen = e.getValue()[1];
            totalPassed += passed;
            total += seen;
            sb.append(String.format("| %s | %d | %d | %s |%n", e.getKey(), passed, seen, pct(passed, seen)));
        }
        sb.append(String.format("| **all** | **%d** | **%d** | **%s** |%n", totalPassed, total, pct(totalPassed, total)));
        Path written = Goldens.writeReport("nlp-dcg", "per-phenomenon.md", sb.toString());
        System.out.println(sb);
        System.out.println("DCG per-phenomenon table written to " + written.toAbsolutePath());
    }

    private static String pct(int passed, int total) {
        return total == 0 ? "n/a" : String.format("%.1f%%", 100.0 * passed / total);
    }
}
