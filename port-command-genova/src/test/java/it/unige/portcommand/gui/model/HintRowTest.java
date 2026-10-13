package it.unige.portcommand.gui.model;

import it.unige.portcommand.assistant.HintCard;
import it.unige.portcommand.assistant.RecommendationCandidate;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17a (ADR-21, decision A3): the row a walk-in tab shows once a Hint has been given — whether
 * there is one, what it says, and what its button would send. Pure model; that the tab draws it,
 * and that a click really sends these words through the pipeline, is {@code ChatPanelHintCardTest}'s
 * job. The counter's sentence has the form of the input's own first example ({@code 1900 for 6h}),
 * which {@link InputExamplesDcgTest} runs through the real grammar.
 */
class HintRowTest {

    private static final String DIALOGUE = "nego-W001";
    private static final int HOURS = 6;

    private static NegotiationOpenedEvent offer(int round, double vesselOffer) {
        VesselSpec spec = new VesselSpec("W001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
        return new NegotiationOpenedEvent(DIALOGUE, WalkInDialogueSnapshot.of(
                "W001", spec, "berth_2", HOURS, vesselOffer, 0.0, round, 0.0, false));
    }

    /** A fresh tab: the vessel opened at €1500 for 6h (round 1). */
    private static NegotiationTabRegistry registryWithOpening() {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(offer(1, 1500.0));
        return registry;
    }

    /** A card made for round 1 of the vessel asking €1500 — the tab {@link #registryWithOpening} builds. */
    private static HintCard card(String action, double price, String suggestion) {
        return new HintCard(action, price, HOURS, 1, 1500.0, suggestion, "EV €640 · chance 62% · R7 ✓");
    }

    private static HintCard counterCard() {
        return card(RecommendationCandidate.COUNTER, 1580.0, "counter at €1580 for 6h");
    }

    private static void hint(NegotiationTabRegistry registry, long hintId, HintCard card) {
        registry.onAssistantChat(AssistantChatEvent.hintTemplate(DIALOGUE, hintId, "template", card));
    }

    // ==================== what "Use this" sends ====================

    @Test
    void anAcceptCardSendsDeal() {
        HintCard accept = card(RecommendationCandidate.ACCEPT, 1500.0, "accept €1500 for 6h");
        assertEquals("deal", HintRow.utteranceFor(accept));
        assertEquals(InputExamples.ACCEPT_UTTERANCE, HintRow.utteranceFor(accept), "the Accept button's own word");
    }

    @Test
    void aSayNoCardSendsNo() {
        // Decision A3: for a "no" recommendation the button sends "no" — the Reject button's word.
        HintCard sayNo = card(RecommendationCandidate.REJECT_SILENT, 0.0, "say no");
        assertEquals("no", HintRow.utteranceFor(sayNo));
        assertEquals(InputExamples.REJECT_UTTERANCE, HintRow.utteranceFor(sayNo));
    }

    @ParameterizedTest(name = "a counter at {0} for {1}h is sent as \"{2}\"")
    @CsvSource({
            "1580.0,  6,  1580 for 6h",
            "1580.4,  6,  1580 for 6h",   // whole euros, as every text about the recommendation shows it
            "1579.5,  6,  1580 for 6h",
            "6200.0, 12,  6200 for 12h",  // no thousands separator: the grammar reads one number
            "12950.0, 24, 12950 for 24h",
    })
    void aCounterCardSendsItsShownPriceAndItsHours(double price, int hours, String expected) {
        HintCard counter = new HintCard(RecommendationCandidate.COUNTER, price, hours, 1, 1500.0,
                "counter at €" + Math.round(price) + " for " + hours + "h", "");
        assertEquals(expected, HintRow.utteranceFor(counter));
        assertEquals(counter.shownPrice() + " for " + counter.hours() + "h", HintRow.utteranceFor(counter),
                "the card's shown price, digit for digit — what the row says is what is sent");
    }

    @Test
    void aCardWithAnActionTheTabHasNoWordsForSendsNothingAndGetsNoRow() {
        HintCard unknown = card("wait", 0.0, "wait and see");
        assertEquals("", HintRow.utteranceFor(unknown));

        NegotiationTabRegistry registry = registryWithOpening();
        hint(registry, 1L, unknown);
        assertEquals(HintRow.State.HIDDEN, HintRow.of(registry.tab(DIALOGUE), false).state(),
                "a button that could send nothing is not offered");
        assertThrows(NullPointerException.class, () -> HintRow.utteranceFor(null));
    }

    // ==================== when there is a row, and what it says ====================

    @Test
    void thereIsNoRowUntilAHintBringsACard() {
        NegotiationTabRegistry registry = registryWithOpening();
        HintRow before = HintRow.of(registry.tab(DIALOGUE), false);
        assertEquals(new HintRow(HintRow.State.HIDDEN, "", ""), before);
        assertFalse(before.visible());
        assertFalse(before.fresh());

        // A Hint without a card (the pre-S17a event shape) shows its text and no row.
        registry.onAssistantChat(AssistantChatEvent.hintTemplate(DIALOGUE, 1L, "template"));
        assertEquals(HintRow.State.HIDDEN, HintRow.of(registry.tab(DIALOGUE), false).state());

        assertEquals(HintRow.State.HIDDEN, HintRow.of(null, false).state(), "a tab that is gone has no row");
    }

    @Test
    void aFreshCardGivesTheSuggestionAndTheWordsToSend() {
        NegotiationTabRegistry registry = registryWithOpening();
        hint(registry, 1L, counterCard());

        HintRow row = HintRow.of(registry.tab(DIALOGUE), false);

        assertEquals(new HintRow(HintRow.State.FRESH, "Assistant suggests: counter at €1580 for 6h", "1580 for 6h"), row);
        assertTrue(row.visible());
        assertTrue(row.fresh());
        assertEquals("Assistant suggests: ", HintRow.SUGGESTS_PREFIX);
    }

    @Test
    void thePlayersOwnOfferDoesNotMakeTheCardStale() {
        // The card answers the VESSEL's offer. The player sending a price of their own (the strip
        // shows it at once) changes neither the round nor what the vessel is asking.
        NegotiationTabRegistry registry = registryWithOpening();
        hint(registry, 1L, counterCard());

        registry.recordPlayerOffer(DIALOGUE, 1900.0, 4, 9L);

        assertEquals(HintRow.State.FRESH, HintRow.of(registry.tab(DIALOGUE), false).state());
    }

    @Test
    void aVesselCounterMakesTheCardStaleAndTakesItsWordsAway() {
        NegotiationTabRegistry registry = registryWithOpening();
        hint(registry, 1L, counterCard());

        registry.onNegotiationOpened(offer(2, 1650.0)); // the vessel countered: a new round, a new price

        HintRow row = HintRow.of(registry.tab(DIALOGUE), false);
        assertEquals(new HintRow(HintRow.State.STALE, "Assistant suggests: counter at €1580 for 6h", ""), row,
                "the row still says what was suggested, but there is nothing left in it to send");
        assertTrue(row.visible());
        assertFalse(row.fresh());
    }

    @ParameterizedTest(name = "round {0}, their offer {1}: stale")
    @CsvSource({
            "2, 1500.0",   // a later round at the same price
            "1, 1650.0",   // the same round number, another price
            "1, 1500.01",  // compared exactly: both values come from snapshots, never from arithmetic
    })
    void eitherTheRoundOrTheVesselsOfferMovingIsEnough(int round, double vesselOffer) {
        NegotiationTabRegistry registry = registryWithOpening();
        hint(registry, 1L, counterCard());

        registry.onNegotiationOpened(offer(round, vesselOffer));

        assertEquals(HintRow.State.STALE, HintRow.of(registry.tab(DIALOGUE), false).state());
    }

    @Test
    void aNewHintMakesTheRowFreshAgain() {
        NegotiationTabRegistry registry = registryWithOpening();
        hint(registry, 1L, counterCard());
        registry.onNegotiationOpened(offer(2, 1650.0));
        assertEquals(HintRow.State.STALE, HintRow.of(registry.tab(DIALOGUE), false).state(), "setup");

        hint(registry, 2L, new HintCard(RecommendationCandidate.ACCEPT, 1650.0, HOURS, 2, 1650.0,
                "accept €1650 for 6h", "EV €560 · chance 100% · R7 ✓"));

        assertEquals(new HintRow(HintRow.State.FRESH, "Assistant suggests: accept €1650 for 6h", "deal"),
                HintRow.of(registry.tab(DIALOGUE), false));
    }

    @Test
    void aUsedCardHasNoRow() {
        NegotiationTabRegistry registry = registryWithOpening();
        hint(registry, 1L, counterCard());

        registry.tab(DIALOGUE).useHintCard();

        assertEquals(HintRow.State.HIDDEN, HintRow.of(registry.tab(DIALOGUE), false).state(), "one card, one send");
    }

    @Test
    void aClosedTabHasNoRow() {
        NegotiationTabRegistry registry = registryWithOpening();
        hint(registry, 1L, counterCard());

        registry.onNegotiationClosed(new NegotiationClosedEvent(DIALOGUE, "W001", Deal.Outcome.TIMEOUT), 0L);

        assertEquals(HintRow.State.HIDDEN, HintRow.of(registry.tab(DIALOGUE), false).state());
    }

    @Test
    void aTabTheAssistantIsNegotiatingInHasNoRowAndGetsItBackWhenThePlayerOwnsIt() {
        NegotiationTabRegistry registry = registryWithOpening();
        hint(registry, 1L, counterCard());

        assertEquals(HintRow.State.HIDDEN, HintRow.of(registry.tab(DIALOGUE), true).state(),
                "the tab offers the player nothing to send while the Assistant holds it");
        assertEquals(HintRow.State.FRESH, HintRow.of(registry.tab(DIALOGUE), false).state(),
                "the card itself was not touched");
    }
}
