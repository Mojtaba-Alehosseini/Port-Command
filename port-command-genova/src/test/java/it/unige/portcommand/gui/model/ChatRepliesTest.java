package it.unige.portcommand.gui.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.NegotiationClosedEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.ontology.Deal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat tab's replies as text (S18a, ADR-23; decisions N6, N7, N8) — the cases
 * {@code ChatPanelTest} does not walk through a tab: the topics that are NOT about the port, the
 * three kinds of instruction, and a help line with nothing to build examples from.
 */
class ChatRepliesTest {

    // --- N6 -------------------------------------------------------------------------------------

    @Test
    void theThreePortTopicsPointAtThePanelThatHoldsTheAnswer() {
        assertTrue(ChatReplies.portAnswer("free_berths").orElseThrow().contains("Port table"));
        assertTrue(ChatReplies.portAnswer("tug_availability").orElseThrow().contains("Tugs line"));
        assertTrue(ChatReplies.portAnswer("weather").orElseThrow().contains("top bar"));
    }

    /** A question about THIS negotiation, a topic the grammar does not have, and no topic at all
     * are not port questions: the tab answers those with its own figures. */
    @ParameterizedTest(name = "[{0}] is not a port question")
    @ValueSource(strings = {"best_price", "berth_assignment", "the_moon", ""})
    void everythingElseIsLeftToTheNegotiationsOwnAnswer(String topic) {
        assertEquals(Optional.empty(), ChatReplies.portAnswer(topic));
    }

    @Test
    void aMissingOrMalformedTopicIsNoTopic() {
        assertEquals(Optional.empty(), ChatReplies.portAnswer(null));
        assertEquals(Optional.empty(), ChatReplies.portAnswer(42));
        assertFalse(ChatReplies.asksForTheBerth(null));
        assertTrue(ChatReplies.asksForTheBerth("berth_assignment"));
    }

    @Test
    void theBerthAnswerNamesTheBerthAndWhoseNegotiationItIs() {
        assertEquals("This negotiation is for berth 3 for 6h — the Port table on the left shows who "
                + "holds each berth.", ChatReplies.berthAnswer(null, "berth_3", 6));
        assertTrue(ChatReplies.berthAnswer("Carthago", "berth_2", 8)
                .startsWith("The negotiation with Carthago is for berth 2 for 8h"));
        assertTrue(ChatReplies.berthAnswer(null, null, 6).startsWith("This negotiation is for a berth not yet named"),
                "no berth id is said as such, never printed as 'null'");
    }

    // --- N7 -------------------------------------------------------------------------------------

    @Test
    void aLimitACommandAndAnotherInstructionEachSayWhatTheyAre() {
        String limit = ChatReplies.notExecuted(Map.of("move", "constrain", "bound", "below"), "1950 for 6h");
        String command = ChatReplies.notExecuted(Map.of("action", "hold", "patient", "tanker"), "1950 for 6h");
        String other = ChatReplies.notExecuted(Map.of("vessel_class", "tanker"), "1950 for 6h");

        assertTrue(limit.startsWith("Understood as a limit for this negotiation"), limit);
        assertFalse(limit.contains("fleet-wide"), "the claim N7 removes: a limit is not fleet-wide");
        assertTrue(command.startsWith("Understood as a fleet-wide command"), command);
        assertTrue(other.startsWith("I read that as an instruction for the port"), other);
        for (String line : List.of(limit, command, other)) {
            assertTrue(line.contains("in this version, so nothing was sent."), line);
            assertTrue(line.endsWith("To move this negotiation write '1950 for 6h'."), line);
        }
    }

    @Test
    void withNoContentOrNoExampleTheLineStillSaysOnlyTrueThings() {
        assertEquals("I read that as an instruction for the port — but instructions are not executed in "
                + "this version, so nothing was sent.", ChatReplies.notExecuted(null, ""));
        assertFalse(ChatReplies.notExecuted(Map.of(), null).contains("To move"),
                "no example to give means no sentence promising one");
    }

    // --- N8 -------------------------------------------------------------------------------------

    @Test
    void theHelpLineIsBuiltFromTheTabsExamples() {
        String help = ChatReplies.help(
                List.of("1950 for 6h", "split the difference", "200 more", "deal", "make it 1800"), true);

        assertEquals("You can type an offer ('1950 for 6h', 'split the difference', '200 more', 'make it 1800'), "
                + "'deal' or 'no', or 'status'. Accept and Reject send 'deal' and 'no'; Hint asks the Assistant "
                + "for a suggestion. Standing policies are set in Settings → Autopilot policy.", help);
    }

    /** While the Assistant negotiates in the tab the Hint button is not on screen, and the line
     * does not point at a button that is not there. */
    @Test
    void theHintIsOnlyMentionedWhenTheButtonIsThere() {
        assertFalse(ChatReplies.help(List.of("1950 for 6h", "deal"), false).contains("Hint"));
        assertTrue(ChatReplies.help(List.of("1950 for 6h", "deal"), true).contains("Hint"));
    }

    @Test
    void withNoExamplesTheHelpLineDescribesAnOfferInstead() {
        String help = ChatReplies.help(List.of(), true);

        assertTrue(help.startsWith("You can type an offer (a price and hours), 'deal' or 'no', or 'status'."), help);
        assertTrue(help.endsWith("Standing policies are set in Settings → Autopilot policy."), help);
    }

    /** The two button words in the help line are the ones the buttons really send. */
    @Test
    void theButtonWordsAreTheOnesTheButtonsSend() {
        String help = ChatReplies.help(List.of(), false);

        assertTrue(help.contains("Accept and Reject send '" + InputExamples.ACCEPT_UTTERANCE + "' and '"
                + InputExamples.REJECT_UTTERANCE + "'"), help);
    }

    // --- S18b (playtest 4, PT22): did you mean --------------------------------------------------

    private static final String DIALOGUE = "nego-W001";

    /** A tab whose vessel offers {@code theirOffer} for 6 h. */
    private static NegotiationTabRegistry tabOffering(double theirOffer) {
        NegotiationTabRegistry registry = new NegotiationTabRegistry();
        registry.onNegotiationOpened(new NegotiationOpenedEvent(DIALOGUE, new WalkInDialogueSnapshot("W001",
                "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", "berth_2", 6, theirOffer, 0.0, 1, 0.0, false,
                "Santa Lucia")));
        return registry;
    }

    @Test
    void oneTypedPriceAboveTheirOfferIsOfferedBackAsAMove() {
        DialogueTabModel tab = tabOffering(1332.0).tab(DIALOGUE);

        assertEquals(Optional.of("Did you mean €2,000? Write '2000 for 6h' — or press Accept for their €1,332."),
                ChatReplies.didYouMean("ok 2000", tab));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ok 2000", "2,000 then", "2.000 ok", "OK 2000!", "fine, 2000"})
    void theWaysAPlayerWritesTwoThousandAreOneNumber(String typed) {
        DialogueTabModel tab = tabOffering(1332.0).tab(DIALOGUE);

        assertTrue(ChatReplies.didYouMean(typed, tab).orElseThrow().startsWith("Did you mean €2,000?"), typed);
    }

    /** At or below their offer, the guess would close the deal under the vessel's own price. */
    @ParameterizedTest
    @ValueSource(strings = {"ok 1000", "ok 1332", "ok 50", "ok", "ok 2000 for 6", "ok 1999.5", "2000 4h", ""})
    void noGuessIsMadeWhenItCouldNotBeSafe(String typed) {
        DialogueTabModel tab = tabOffering(1332.0).tab(DIALOGUE);

        assertEquals(Optional.empty(), ChatReplies.didYouMean(typed, tab), typed);
    }

    @Test
    void theHoursAreThePlayersOwnLastOnes() {
        NegotiationTabRegistry registry = tabOffering(1332.0);
        registry.recordPlayerOffer(DIALOGUE, 2400.0, 4);

        assertTrue(ChatReplies.didYouMean("ok 2000", registry.tab(DIALOGUE)).orElseThrow().contains("'2000 for 4h'"));
    }

    @Test
    void aClosedTabOrNoTabGetsNoGuess() {
        NegotiationTabRegistry registry = tabOffering(1332.0);
        assertEquals(Optional.empty(), ChatReplies.didYouMean("ok 2000", null));
        assertEquals(Optional.empty(), ChatReplies.didYouMean(null, registry.tab(DIALOGUE)));

        registry.onNegotiationClosed(new NegotiationClosedEvent(DIALOGUE, "W001", Deal.Outcome.TIMEOUT), 0L);
        assertEquals(Optional.empty(), ChatReplies.didYouMean("ok 2000", registry.tab(DIALOGUE)));
    }
}
