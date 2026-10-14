package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PlayerCommandRefusedEvent;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.VesselSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the rotating example in a walk-in tab's empty input field — which sentences,
 * with which numbers, in which order, and when it moves on. Pure model; whether the grammar really
 * parses each sentence is {@link InputExamplesDcgTest}'s job.
 */
class InputExamplesTest {

    private static final String DIALOGUE = "nego-W001";

    private static VesselSpec spec() {
        return new VesselSpec("W001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L);
    }

    private static NegotiationOpenedEvent offer(int roundsUsed, double vesselOffer, int hours) {
        return new NegotiationOpenedEvent(DIALOGUE, WalkInDialogueSnapshot.of(
                "W001", spec(), "berth_2", hours, vesselOffer, 0.0, roundsUsed, 0.0, false));
    }

    /** A fresh tab: the vessel opened at {@code vesselOffer} for {@code hours}, the player has not answered. */
    private static NegotiationTabRegistry registryWithOpening(double vesselOffer, int hours) {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(offer(1, vesselOffer, hours));
        return registry;
    }

    private static PlayerCommandRefusedEvent refused(long commandId, double standingPrice) {
        return new PlayerCommandRefusedEvent(DIALOGUE, PlayerCommandEvent.PlayerCommandKind.PROPOSE, commandId,
                standingPrice, "berth_2 is incompatible with a ferry [R7]");
    }

    // ==================== the numbers: always above the vessel's standing offer ====================

    @ParameterizedTest(name = "their offer {0} -> ask {1}")
    @CsvSource({
            "1436, 1900",  // 1866.8 -> the next 50
            "1529, 2000",  // 1987.7
            "1500, 1950",  // exactly on a multiple: stays there (and survives 1500 * 1.30 in binary)
            "1000, 1300",
            "400,  550",
            "4500, 5850",
            "6000, 7800",
            "9950, 12950",
    })
    void thePriceExampleIsThirtyPercentUpRoundedUpToTheNextFifty(double theirOffer, long expected) {
        assertEquals(expected, InputExamples.priceExample(theirOffer));
    }

    @ParameterizedTest(name = "their offer {0} -> ask {1}")
    @CsvSource({
            "300, 400",  // 390 -> 400, which is exactly the +100 floor
            "200, 300",  // 260 -> 300 = 200 + 100
            "120, 220",  // 156 -> 200, but never less than 100 over
            "100, 200",
            "10,  110",
    })
    void thePriceExampleIsNeverLessThanAHundredOverASmallOffer(double theirOffer, long expected) {
        assertEquals(expected, InputExamples.priceExample(theirOffer));
    }

    @ParameterizedTest(name = "their offer {0} -> make it {1}")
    @CsvSource({
            "1436, 1750",  // 1723.2 -> the next 50
            "1529, 1850",  // 1834.8
            "1500, 1800",
            "400,  500",
            "4500, 5400",
            "6000, 7200",
            "9950, 11950",
            "100,  150",   // 120 -> 150 = 100 + 50
            "10,   60",    // 12 -> 50, but never less than 50 over
    })
    void theMakeItExampleIsTwentyPercentUpRoundedUpToTheNextFifty(double theirOffer, long expected) {
        assertEquals(expected, InputExamples.makeItExample(theirOffer));
    }

    @ParameterizedTest(name = "their offer {0}: ask {1}, make it {2}")
    @CsvSource({
            "300, 400, 350",  // both formulas land on 400
            "340, 450, 400",  // both land on 450
            "420, 550, 500",  // both land on 550
    })
    void theMakeItExampleStepsFiftyDownRatherThanRepeatThePriceExample(double theirOffer, long price, long makeIt) {
        assertEquals(price, InputExamples.priceExample(theirOffer), "setup");
        assertEquals(makeIt, InputExamples.makeItExample(theirOffer));
    }

    @Test
    void bothPricesAreAlwaysAboveTheVesselsOfferAndNeverTheSame() {
        // The vessel accepts any demand at or below its standing offer, so an example at or under
        // it would close the deal on the vessel's terms the moment the player copied it.
        for (double theirOffer = 1.0; theirOffer <= 20_000.0; theirOffer += 0.5) {
            long price = InputExamples.priceExample(theirOffer);
            long makeIt = InputExamples.makeItExample(theirOffer);
            String at = " at their offer " + theirOffer;
            assertTrue(price >= theirOffer + 100 - 1e-6, "ask " + price + " is not 100 over" + at);
            assertTrue(makeIt >= theirOffer + 50 - 1e-6, "make it " + makeIt + " is not 50 over" + at);
            assertTrue(makeIt < price, "make it " + makeIt + " must stay under the ask " + price + at);
        }
    }

    @Test
    void aLargeOfferGetsAMultipleOfFifty() {
        for (double theirOffer = 400.0; theirOffer <= 20_000.0; theirOffer += 1.0) {
            assertEquals(0, InputExamples.priceExample(theirOffer) % 50, "ask at their offer " + theirOffer);
        }
    }

    @Test
    void theHoursAreTheHoursUnderDiscussionKeptInsideWhatTheGrammarAccepts() {
        assertEquals(6, InputExamples.hoursExample(6));
        assertEquals(1, InputExamples.hoursExample(1));
        assertEquals(24, InputExamples.hoursExample(24));
        assertEquals(24, InputExamples.hoursExample(30), "the grammar refuses 'for 30h' outright");
        assertEquals(1, InputExamples.hoursExample(0));
        assertEquals(1, InputExamples.hoursExample(-3));
    }

    @Test
    void pricesArePrintedAsWholeEurosWithNoSeparators() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY); // would group as 12.950
            List<String> examples = InputExamples.examplesFor(registryWithOpening(9950.0, 18).tab(DIALOGUE));

            assertEquals("12950 for 18h", examples.get(0));
            assertEquals("make it 11950", examples.get(examples.size() - 1));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    // ==================== the rotation ====================

    @Test
    void aTabWithNoPlayerOfferCyclesItsThreeExamples() {
        DialogueTabModel tab = registryWithOpening(1436.0, 6).tab(DIALOGUE);

        assertEquals(List.of("1900 for 6h", "200 more", "make it 1750"), InputExamples.examplesFor(tab));
        List<String> shown = new ArrayList<>();
        for (int sends = 0; sends < 7; sends++) {
            shown.add(InputExamples.exampleAt(tab, sends));
        }
        assertEquals(List.of("1900 for 6h", "200 more", "make it 1750",
                "1900 for 6h", "200 more", "make it 1750", "1900 for 6h"), shown);
    }

    /** S18b (UI tree U2): an example under the player's own demand teaches conceding. */
    @Test
    void noPriceShownIsBelowThePlayersOwnDemand() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0, 6);
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 6);
        DialogueTabModel tab = registry.tab(DIALOGUE);
        assertEquals(List.of("1900 for 6h", "200 more"), InputExamples.examplesFor(tab),
                "'make it 1750' would concede 150 of the 1900 asked: left out");
        assertEquals("1900 for 6h", InputExamples.exampleAt(tab, 2), "two examples now, then round again");

        registry.recordPlayerOffer(DIALOGUE, 1975.0, 6);
        assertEquals(List.of("2000 for 6h", "200 more"), InputExamples.examplesFor(tab),
                "the demand rounded UP to the next 50: never below it");

        registry.recordPlayerOffer(DIALOGUE, 1800.0, 6);
        assertEquals(List.of("1900 for 6h", "200 more", "make it 1800"), InputExamples.examplesFor(tab),
                "a demand between the two prices lifts only the lower one");
    }

    /** S18b (UI tree U2): copied, "deal" takes the vessel's lowest price for good; "split the
     * difference" is below the player's own demand by construction. Neither is an example. */
    @Test
    void neitherDealNorSplitTheDifferenceIsEverShown() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0, 6);
        DialogueTabModel tab = registry.tab(DIALOGUE);
        for (int round = 0; round < 2; round++) {
            for (int sends = 0; sends <= 20; sends++) {
                String example = InputExamples.exampleAt(tab, sends);
                assertFalse(example.contains("split"), "after " + sends + " sends: " + example);
                assertFalse(example.equals(InputExamples.ACCEPT_UTTERANCE), "after " + sends + " sends: " + example);
            }
            registry.recordPlayerOffer(DIALOGUE, 1900.0, 6); // and again with an offer standing
        }
    }

    @Test
    void noExampleEverRefusesTheVessel() {
        // "too low", "no", "no deal", "reject" all END the negotiation, with no undo.
        NegotiationTabRegistry registry = registryWithOpening(1436.0, 6);
        List<String> all = new ArrayList<>(InputExamples.examplesFor(registry.tab(DIALOGUE)));
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 6);
        all.addAll(InputExamples.examplesFor(registry.tab(DIALOGUE)));

        assertEquals(5, all.size(), "setup: both rotations");
        for (String example : all) {
            assertFalse(example.equals(InputExamples.REJECT_UTTERANCE),
                    "the Reject literal is a button, not an example");
            for (String refusal : List.of("too low", "too high", "no deal", "reject", "refuse", "not interested",
                    "forget it", "pass")) {
                assertFalse(example.contains(refusal), "'" + example + "' would turn the vessel away");
            }
        }
    }

    @Test
    void theButtonLiteralsAreDealAndNo() {
        assertEquals("deal", InputExamples.ACCEPT_UTTERANCE);
        assertEquals("no", InputExamples.REJECT_UTTERANCE);
    }

    // ==================== when it moves on ====================

    @Test
    void thePlaceholderIsTheCurrentExampleBehindEg() {
        DialogueTabModel tab = registryWithOpening(1436.0, 6).tab(DIALOGUE);

        assertEquals("e.g. 1900 for 6h", InputExamples.placeholderFor(tab));
        assertEquals("1900 for 6h", InputExamples.priceAndHoursExample(tab),
                "the one worked example other text quotes is the same sentence");
    }

    @Test
    void itAdvancesOnASendAndNotOnAVesselMessage() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0, 6);
        DialogueTabModel tab = registry.tab(DIALOGUE);
        assertEquals(0, tab.playerMessageCount(), "setup: the orientation and the opening are not sends");
        assertEquals("e.g. 1900 for 6h", InputExamples.placeholderFor(tab));

        // Everything that is not the player sending: the vessel, the Assistant, a system notice.
        tab.appendVesselMessage("We can wait a little longer.");
        tab.appendAssistantMessage("Assistant: hold your price.");
        tab.appendSystemMessage("I didn't get that.");
        assertTrue(tab.appendHint(1L, "template"));
        assertEquals("e.g. 1900 for 6h", InputExamples.placeholderFor(tab), "nobody sent anything: it stays put");

        tab.appendPlayerMessage("hello there");
        assertEquals("e.g. 200 more", InputExamples.placeholderFor(tab), "one send, one step");
        tab.appendPlayerMessage("what berths are free");
        assertEquals("e.g. make it 1750", InputExamples.placeholderFor(tab));
    }

    @Test
    void rightAfterThePlayersFirstOfferTheExampleIsTwoHundredMore() {
        // The natural first turn: the player copies the price example. One send, and an offer now
        // stands — index 1 of the two-example rotation (S18b, U2: nothing under the 1900 asked).
        NegotiationTabRegistry registry = registryWithOpening(1436.0, 6);
        DialogueTabModel tab = registry.tab(DIALOGUE);
        tab.appendPlayerMessage("1900 for 6h");
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 6, 1L);

        assertEquals("e.g. 200 more", InputExamples.placeholderFor(tab));
    }

    @Test
    void aRefusedFirstOfferBringsTheLowerExampleBack() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0, 6);
        DialogueTabModel tab = registry.tab(DIALOGUE);
        tab.appendPlayerMessage("1900 for 6h");
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 6, 7L);
        assertFalse(InputExamples.examplesFor(tab).contains("make it 1750"), "setup: offered 1900 at the send");

        registry.onPlayerCommandRefused(refused(7L, 0.0)); // the Prolog gate said no: nothing stands

        assertFalse(tab.playerHasOffered());
        assertEquals(List.of("1900 for 6h", "200 more", "make it 1750"), InputExamples.examplesFor(tab),
                "no demand stands, so nothing lifts the lower price");
        assertEquals("e.g. 200 more", InputExamples.placeholderFor(tab),
                "the send still counts as a send");
    }

    @Test
    void theNumbersFollowTheVesselsStandingOfferAndItsHours() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0, 6);
        registry.onNegotiationOpened(offer(2, 1529.0, 8)); // the vessel counters, and now wants 8 h

        DialogueTabModel tab = registry.tab(DIALOGUE);

        assertEquals("e.g. 2000 for 8h", InputExamples.placeholderFor(tab));
        assertEquals("make it 1850", InputExamples.exampleAt(tab, 2));
    }

    @Test
    void aVesselOfferingSixThousandIsNeverShownAPriceBelowItsOffer() {
        // The case that ruled out a fixed "1900 for 6h": copied here it would close at 1900.
        DialogueTabModel tab = registryWithOpening(6000.0, 6).tab(DIALOGUE);

        assertEquals(List.of("7800 for 6h", "200 more", "make it 7200"), InputExamples.examplesFor(tab));
    }

    // ==================== nothing to show ====================

    @Test
    void aClosedTabHasNoPlaceholder() {
        NegotiationTabRegistry registry = registryWithOpening(1436.0, 6);
        registry.recordPlayerOffer(DIALOGUE, 1900.0, 6);
        registry.onNegotiationClosed(new NegotiationClosedEvent(DIALOGUE, "W001", Deal.Outcome.DEAL), 0L);
        DialogueTabModel tab = registry.tab(DIALOGUE);

        assertEquals("", InputExamples.placeholderFor(tab));
        assertEquals(List.of(), InputExamples.examplesFor(tab));
        assertEquals("", InputExamples.exampleAt(tab, 3));
        assertEquals("", InputExamples.priceAndHoursExample(tab));
    }

    @Test
    void aTabWithNoVesselOfferHasNoExamples() {
        // Never built in play (a tab is born from the vessel's opening offer), but with nothing on
        // the table none of the price examples could parse — so show none rather than a wrong one.
        DialogueTabModel tab = new DialogueTabModel(DIALOGUE, WalkInDialogueSnapshot.of(
                "W001", spec(), "berth_2", 6, 0.0, 0.0, 1, 0.0, false));

        assertEquals(List.of(), InputExamples.examplesFor(tab));
        assertEquals("", InputExamples.placeholderFor(tab));
    }
}
