package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.nlp.DcgTokenizer;
import it.unige.portcommand.nlp.DialogueCtx;
import it.unige.portcommand.nlp.Frame;
import it.unige.portcommand.nlp.FrameToAcl;
import it.unige.portcommand.nlp.OntologyValidator;
import it.unige.portcommand.nlp.PrologDcgParser;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologEngine;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * S15 (2026-10-12): every example the input field can show, run through the REAL negotiation
 * grammar ({@code dcg_negotiation.pl} over JPL) in the very context the tab would send it with.
 * The engine and parser are set up as in {@code DCGParserTest}; the contexts are NOT hand-built —
 * they come from {@link NegotiationTabRegistry#buildCtx}, the production builder, so a change to
 * how the tab describes its state to the grammar is caught here too.
 *
 * <p>Unit lane (no tag), like {@code DCGParserTest}: an example that stops parsing is a broken
 * prompt on every tab, and that must fail the fast gate.
 *
 * <p>What it proves, over a spread of standing offers and hours and in both tab states (before
 * and after the player's first offer): every shown example parses; a priced one is a counter at
 * exactly the price it prints (and the hours it prints, where it prints any), above the vessel's
 * standing offer; none is a reject; and the two button literals mean accept and reject everywhere.
 */
class InputExamplesDcgTest {

    private static final String VESSEL = "W001";
    private static final String DIALOGUE = "nego-W001";
    private static final String BERTH = "berth_2";

    /** Small, the demo's two openings, mid, the fixtures' tanker, and one that prints five digits. */
    private static final double[] OFFERS = {400, 1436, 1529, 4500, 6000, 9950};
    /** Both ends of what the grammar accepts (1..24), and two in between. */
    private static final int[] HOURS = {1, 6, 18, 24};

    private static final Pattern PRICE_AND_HOURS = Pattern.compile("(\\d+) for (\\d+)h");
    private static final Pattern MAKE_IT = Pattern.compile("make it (\\d+)");

    private static PrologDcgParser parser;
    private static OntologyValidator validator;

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
        parser = new PrologDcgParser(PrologEngine.getInstance(), new DcgTokenizer());
        // Production's own wiring (JadeBootstrap). Its WordNet dictionary loads lazily, on a
        // vessel-class word only — no example carries one, so this stays in the fast lane.
        validator = new OntologyValidator();
    }

    static Stream<Arguments> offersAndHours() {
        List<Arguments> cases = new ArrayList<>();
        for (double offer : OFFERS) {
            for (int hours : HOURS) {
                cases.add(Arguments.of(offer, hours));
            }
        }
        return cases.stream();
    }

    private static NegotiationOpenedEvent opening(String dialogueId, String vesselId, String type, String berthId,
                                                  int hours, double vesselOffer) {
        VesselSpec spec = new VesselSpec(vesselId, type, 9.0, 150.0, 30000, "general_cargo", 0L);
        return new NegotiationOpenedEvent(dialogueId, WalkInDialogueSnapshot.of(
                vesselId, spec, berthId, hours, vesselOffer, 0.0, 1, 0.0, false));
    }

    /** A fresh tab for {@link #VESSEL}: it opened at {@code vesselOffer} for {@code hours}. */
    private static NegotiationTabRegistry freshTab(double vesselOffer, int hours) {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(opening(DIALOGUE, VESSEL, "cargo_vessel", BERTH, hours, vesselOffer));
        return registry;
    }

    /** A second walk-in, opened AFTER the first so it is the most recent entry of the roster. */
    private static void openSecondVessel(NegotiationTabRegistry registry) {
        registry.onNegotiationOpened(opening("nego-W002", "W002", "tanker", "berth_1", 8, 6000.0));
    }

    private static double moneyAmount(Frame frame) {
        Object money = frame.element("money");
        assertTrue(money instanceof Map, "money must decode to {amount, currency}: " + money);
        return ((Number) ((Map<?, ?>) money).get("amount")).doubleValue();
    }

    /**
     * Parses every example {@link InputExamples} shows for the registry's {@link #DIALOGUE} tab,
     * under the context that tab would build, and checks each one means what it prints.
     *
     * @return how many examples were checked (so a caller can pin the rotation's size)
     */
    private static int assertEveryShownExampleParses(NegotiationTabRegistry registry, String where) {
        DialogueTabModel tab = registry.tab(DIALOGUE);
        WalkInDialogueSnapshot s = tab.snapshot();
        DialogueCtx ctx = registry.buildCtx(VESSEL);
        List<String> examples = InputExamples.examplesFor(tab);
        assertFalse(examples.isEmpty(), "an open tab with an offer on the table shows examples" + where);

        for (String example : examples) {
            String what = "'" + example + "'" + where;
            Optional<Frame> parsed = parser.parse(example, ctx);
            assertTrue(parsed.isPresent(), "shown in the input field, but the grammar does not parse it: " + what);
            Frame frame = parsed.get();
            assertTrue(validator.test(frame, example), "the ontology validator would stop it: " + what);
            assertNotEquals("reject", frame.move(), "an example must never turn the vessel away: " + what);
            assertNull(frame.element("addressee"), "an example is for the tab it is typed in: " + what);

            if (example.equals(InputExamples.ACCEPT_UTTERANCE)) {
                assertEquals(Map.of(Frame.MOVE, "accept"), frame.elements(), what);
                assertEquals(ACLMessage.ACCEPT_PROPOSAL,
                        FrameToAcl.build(frame, null, ctx.activeNegotiationId()).getPerformative(), what);
                continue;
            }

            // Everything else is a priced counter: the tab sends it as a PROPOSE.
            assertEquals("counter", frame.move(), what);
            assertEquals(ACLMessage.PROPOSE,
                    FrameToAcl.build(frame, null, ctx.activeNegotiationId()).getPerformative(), what);
            assertEquals(BERTH, frame.element("berth"), "completed from the offer on the table: " + what);
            double price = moneyAmount(frame);
            assertTrue(price > s.lastVesselOffer(),
                    "the vessel takes any demand at or below its offer, so this would close on ITS terms: " + what);

            Matcher priceAndHours = PRICE_AND_HOURS.matcher(example);
            Matcher makeIt = MAKE_IT.matcher(example);
            if (priceAndHours.matches()) {
                assertEquals(Double.parseDouble(priceAndHours.group(1)), price, "the price it prints: " + what);
                // Prolog integers decode as Long (Frame.decodeValue) — compare as long.
                assertEquals(Long.parseLong(priceAndHours.group(2)), frame.element("duration"),
                        "the hours it prints: " + what);
                assertEquals((long) s.durationHours(), frame.element("duration"),
                        "which are the hours under discussion: " + what);
            } else if (makeIt.matches()) {
                assertEquals(Double.parseDouble(makeIt.group(1)), price, "the price it prints: " + what);
                assertEquals((long) s.durationHours(), frame.element("duration"),
                        "no hours printed: the hours under discussion ride along: " + what);
            } else if (example.equals("200 more")) {
                // The grammar's reference offer: the player's last bid if one stands, else the vessel's ask.
                double reference = tab.playerHasOffered() ? s.lastPlayerOffer() : s.lastVesselOffer();
                assertEquals(reference + 200, price, what);
                assertEquals((long) s.durationHours(), frame.element("duration"), what);
            } else if (example.equals("split the difference")) {
                assertTrue(tab.playerHasOffered(), "only shown once both sides have named a price: " + what);
                assertEquals((double) Math.round((s.lastVesselOffer() + s.lastPlayerOffer()) / 2.0), price, what);
                assertEquals((long) s.durationHours(), frame.element("duration"), what);
            } else {
                fail("an example this test does not know how to prove: " + what);
            }
        }
        return examples.size();
    }

    // ==================== before the player's first offer ====================

    @ParameterizedTest(name = "their offer {0} for {1}h, no offer of mine yet")
    @MethodSource("offersAndHours")
    void everyExampleShownBeforeAnOfferParsesInThatContext(double vesselOffer, int hours) {
        NegotiationTabRegistry registry = freshTab(vesselOffer, hours);

        int checked = assertEveryShownExampleParses(registry,
                " (their offer " + vesselOffer + " for " + hours + "h, before my first offer)");

        assertEquals(4, checked, "price-and-hours, 200 more, deal, make it");
    }

    @ParameterizedTest(name = "their offer {0} for {1}h: split the difference does not parse yet")
    @MethodSource("offersAndHours")
    void splitTheDifferenceDoesNotParseBeforeAnOffer(double vesselOffer, int hours) {
        // The reason the rotation is state-aware: with only the vessel's price on the table there
        // is nothing to split, and the grammar refuses the sentence outright.
        NegotiationTabRegistry registry = freshTab(vesselOffer, hours);

        assertEquals(Optional.empty(), parser.parse("split the difference", registry.buildCtx(VESSEL)));
        assertFalse(InputExamples.examplesFor(registry.tab(DIALOGUE)).contains("split the difference"));
    }

    // ==================== once the player has an offer standing ====================

    @ParameterizedTest(name = "their offer {0} for {1}h, my offer standing")
    @MethodSource("offersAndHours")
    void everyExampleShownAfterAnOfferParsesInThatContext(double vesselOffer, int hours) {
        NegotiationTabRegistry registry = freshTab(vesselOffer, hours);
        // The natural first turn: the player sent the price example itself.
        double myOffer = InputExamples.priceExample(vesselOffer);
        registry.recordPlayerOffer(DIALOGUE, myOffer, hours);

        int checked = assertEveryShownExampleParses(registry,
                " (their offer " + vesselOffer + " for " + hours + "h, my offer " + myOffer + " standing)");

        assertEquals(5, checked, "price-and-hours, split the difference, 200 more, deal, make it");
    }

    @Test
    void theDemoTabReadsExactlyAsDesigned() {
        // Santa Lucia's opening (1436 for 6 h), then the player's 1900: the numbers written down
        // in the design, end to end through the real grammar.
        NegotiationTabRegistry registry = freshTab(1436.0, 6);
        DialogueCtx before = registry.buildCtx(VESSEL);
        assertEquals(1900.0, moneyAmount(parser.parse("1900 for 6h", before).orElseThrow()));
        assertEquals(1636.0, moneyAmount(parser.parse("200 more", before).orElseThrow()), "their 1436 + 200");
        assertEquals(1750.0, moneyAmount(parser.parse("make it 1750", before).orElseThrow()));

        registry.recordPlayerOffer(DIALOGUE, 1900.0, 6);
        DialogueCtx after = registry.buildCtx(VESSEL);
        assertEquals(1668.0, moneyAmount(parser.parse("split the difference", after).orElseThrow()),
                "midway between their 1436 and my 1900");
        assertEquals(2100.0, moneyAmount(parser.parse("200 more", after).orElseThrow()), "my 1900 + 200");
        assertEquals(List.of("1900 for 6h", "split the difference", "200 more", "deal", "make it 1750"),
                InputExamples.examplesFor(registry.tab(DIALOGUE)), "and those are the sentences shown");
    }

    @Test
    void theExamplesStillParseWhenThePlayerAskedForOtherHoursAndFarMore() {
        // The player's own hours live on the tab, not in the grammar's context (both standing
        // offers carry the hours under discussion), and their price may be far above the examples.
        NegotiationTabRegistry registry = freshTab(1529.0, 6);
        registry.recordPlayerOffer(DIALOGUE, 4000.0, 4);

        assertEquals(5, assertEveryShownExampleParses(registry, " (their 1529 for 6h, my 4000 for 4h)"));
    }

    @Test
    void theExamplesStillParseAgainstAnOfferThatIsNotAWholeNumber() {
        NegotiationTabRegistry registry = freshTab(1436.5, 6);
        assertEquals(4, assertEveryShownExampleParses(registry, " (their 1436.5 for 6h, before my first offer)"));

        registry.recordPlayerOffer(DIALOGUE, 1900.0, 6);
        assertEquals(5, assertEveryShownExampleParses(registry, " (their 1436.5 for 6h, my 1900 standing)"));
    }

    // ==================== a second vessel in the roster ====================

    @ParameterizedTest(name = "their offer {0} for {1}h, a tanker also waiting")
    @MethodSource("offersAndHours")
    void theExamplesStillParseWithASecondVesselOpen(double vesselOffer, int hours) {
        NegotiationTabRegistry registry = freshTab(vesselOffer, hours);
        openSecondVessel(registry);
        assertEquals(2, registry.buildCtx(VESSEL).roster().size(), "setup: two dialogues in the roster");

        assertEquals(4, assertEveryShownExampleParses(registry,
                " (their offer " + vesselOffer + " for " + hours + "h, a tanker also waiting, before my first offer)"));

        registry.recordPlayerOffer(DIALOGUE, InputExamples.priceExample(vesselOffer), hours);
        assertEquals(5, assertEveryShownExampleParses(registry,
                " (their offer " + vesselOffer + " for " + hours + "h, a tanker also waiting, my offer standing)"));
    }

    // ==================== the two buttons ====================

    @ParameterizedTest(name = "their offer {0} for {1}h: deal accepts, no rejects")
    @MethodSource("offersAndHours")
    void theButtonUtterancesParseAsAcceptAndRejectInEveryContext(double vesselOffer, int hours) {
        NegotiationTabRegistry registry = freshTab(vesselOffer, hours);
        List<DialogueCtx> contexts = new ArrayList<>();
        contexts.add(registry.buildCtx(VESSEL));                    // fresh tab
        registry.recordPlayerOffer(DIALOGUE, InputExamples.priceExample(vesselOffer), hours);
        contexts.add(registry.buildCtx(VESSEL));                    // the player's offer standing
        openSecondVessel(registry);
        contexts.add(registry.buildCtx(VESSEL));                    // and a second vessel waiting
        contexts.add(DialogueCtx.NONE);                             // nothing at all on the table

        for (DialogueCtx ctx : contexts) {
            Frame accept = parser.parse(InputExamples.ACCEPT_UTTERANCE, ctx).orElseThrow();
            assertEquals(Map.of(Frame.MOVE, "accept"), accept.elements(), "Accept sends 'deal' under " + ctx);
            assertEquals(ACLMessage.ACCEPT_PROPOSAL, FrameToAcl.build(accept, null, null).getPerformative());

            Frame reject = parser.parse(InputExamples.REJECT_UTTERANCE, ctx).orElseThrow();
            assertEquals(Map.of(Frame.MOVE, "reject"), reject.elements(), "Reject sends 'no' under " + ctx);
            assertEquals(ACLMessage.REJECT_PROPOSAL, FrameToAcl.build(reject, null, null).getPerformative());
        }
    }
}
