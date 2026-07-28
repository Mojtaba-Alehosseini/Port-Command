package it.unige.portcommand.nlp.regression;

import java.util.List;
import java.util.Optional;

import it.unige.portcommand.nlp.DCGParser;
import it.unige.portcommand.nlp.DcgTokenizer;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.Frame;
import it.unige.portcommand.nlp.PrologDcgParser;
import it.unige.portcommand.prolog.PrologEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit D-01 (2026-07-27) — the demo documents' own typed utterances must be in the grammar.
 *
 * <p>{@code DEMO_SCRIPT.md} Beat 3 told the presenter to <b>type</b> {@code 1900 for 6 hours at
 * berth 3} and then say <i>"The DCG parsed it — Rasa was never called."</i> The DCG could not parse
 * that string. Traced clause by clause over {@code dcg_negotiation.pl}: {@code propose_move} needs a
 * {@code give_verb}; both {@code counter_move} clauses need a {@code counter_phrase} (leading, or
 * trailing after the slots); {@code ellipsis_move} clause 1 is the only rule accepting a verbless,
 * cue-less price and it has <b>no berth slot</b>, so it consumed {@code [1900, for, 6, h]} and left
 * the residue {@code [at, berth, 3]}, which {@code residue_ignorable/1} rejects. The turn therefore
 * fell through to Rasa, which classifies it {@code propose_offer} — and {@code NLPPipeline} routes
 * {@code propose_offer}/{@code counter_offer} to <b>clarification</b>. On stage that is
 * <i>"I didn't get that — use the buttons below…"</i>, immediately after the claim that the DCG
 * worked. {@code DEMO_CHECKLIST.md} Row 2 used a different string that did parse, so the two demo
 * documents disagreed and the one Moji is told to print and hold was the broken one.
 *
 * <p>The fix was to correct the sentence, not the grammar (a freeze-safe one-line doc change versus
 * new goldens, a PLUnit case and a re-check of the one-parse-per-utterance property). This gate is
 * what stops the documents drifting away from the grammar again: <b>if you change the typed line in
 * either demo document, change it here too and watch it pass.</b>
 */
@Tag("integration")
class DemoUtteranceIT {

    private static DCGParser parser;

    /** Beat 3's counter, as {@code docs/DEMO_SCRIPT.md} now words it. */
    private static final String DEMO_SCRIPT_BEAT_3 = "how about 1900 for 6 hours at berth 3";

    /** Row 2's counter, as {@code docs/DEMO_CHECKLIST.md} words it (berth completed from context). */
    private static final String DEMO_CHECKLIST_ROW_2 = "1900 for 6 hours";

    /** The pre-fix Beat 3 string, kept as a negative control so the bug cannot come back unnoticed. */
    private static final String THE_STRING_THAT_DID_NOT_PARSE = "1900 for 6 hours at berth 3";

    /** Mirrors {@code DcgRegressionIT.FOCUS}: Genoa Star (cargo) asked 2000 for 5 h at berth_3. */
    private static final DialogueCtx FOCUS = new DialogueCtx(
            "C001",
            new DialogueCtx.StandingOffer(new DialogueCtx.OfferView(2000.0, 5, "berth_3"),
                    new DialogueCtx.OfferView(1500.0, 5, "berth_3")),
            List.of(new DialogueCtx.RosterEntry("C001", "cargo_vessel", "Genoa Star", "berth_3", 5),
                    new DialogueCtx.RosterEntry("T001", "tanker", "Carthago", "berth_2", 8)),
            "berth_3");

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
        parser = new PrologDcgParser(PrologEngine.getInstance(), new DcgTokenizer());
    }

    @Test
    void theDemoScriptsBeat3UtteranceParsesWithAllThreeSlots() {
        Optional<Frame> frame = parser.parse(DEMO_SCRIPT_BEAT_3, FOCUS);
        assertTrue(frame.isPresent(),
                "DEMO_SCRIPT.md Beat 3 types this and then claims the DCG parsed it: " + DEMO_SCRIPT_BEAT_3);
        Frame f = frame.get();
        assertEquals("counter", f.move(), "Beat 3 is a counter-offer");
        assertEquals(1900.0, moneyAmount(f), "the price the presenter says out loud");
        // Prolog integers decode through Frame.decodeValue as LONG (t.longValue()), so the
        // expected value must be 6L — an int literal boxes to Integer and never equals Long(6).
        assertEquals(6L, f.element("duration"), "the hours the presenter says out loud");
        assertEquals("berth_3", f.element("berth"), "the berth the presenter says out loud");
    }

    @Test
    void theDemoChecklistsRow2UtteranceParsesAndCompletesFromContext() {
        Optional<Frame> frame = parser.parse(DEMO_CHECKLIST_ROW_2, FOCUS);
        assertTrue(frame.isPresent(),
                "DEMO_CHECKLIST.md Row 2 types this: " + DEMO_CHECKLIST_ROW_2);
        assertEquals(1900.0, moneyAmount(frame.get()));
        assertEquals(6L, frame.get().element("duration"));
    }

    /**
     * The negative control. If this ever starts parsing — because someone adds {@code optional_berth}
     * to {@code ellipsis_move} clause 1, which is the correct long-term fix — delete this test and
     * put the shorter sentence back in the demo script. Until then it records exactly what was
     * wrong, so "the demo script's string doesn't parse" cannot quietly become folklore.
     */
    @Test
    void theOriginalBeat3StringStillDoesNotParseWhichIsWhyTheScriptChanged() {
        assertTrue(parser.parse(THE_STRING_THAT_DID_NOT_PARSE, FOCUS).isEmpty(),
                "if this now parses, restore the shorter sentence in DEMO_SCRIPT.md Beat 3 and "
                        + "delete this test — the grammar caught up with the documentation");
    }

    private static double moneyAmount(Frame frame) {
        Object money = frame.element("money");
        assertTrue(money instanceof java.util.Map, "money must decode to {amount, currency}: " + money);
        return ((Number) ((java.util.Map<?, ?>) money).get("amount")).doubleValue();
    }
}
