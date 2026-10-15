package it.unige.portcommand.nlp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input-safety rules on their own (S18a, ADR-23) — the edges {@code NLPPipelineTest} does not
 * reach through a pipeline: where each threshold sits, what a frame must be to be judged at all,
 * and how an amount is written back to the player.
 */
class InputSafetyTest {

    /** S19 (playtest 5, PT27): typing "hint" sent a vessel away - Rasa read it as a refusal. The
     * request for the Hint is read whole, before Rasa, and never when the line says more. */
    @Test
    void aRequestForTheHintIsReadWhole() {
        for (String line : List.of("hint", "Hint!", "a hint please", "give me a hint", "suggest",
                "any suggestions?", "what do you suggest", "advice", "Any advice?", "what should I do",
                "What should I do now?")) {
            assertTrue(InputSafety.isHintRequest(line), line);
        }
        for (String line : List.of("help", "no", "I suggest 2000 for 6h", "hint 2000", "my advice is no deal",
                "what should I do with berth 2", "status", "")) {
            assertFalse(InputSafety.isHintRequest(line), line);
        }
        assertFalse(InputSafety.isHintRequest(null));
    }

    private static Frame frame(String move, Object money) {
        Map<String, Object> elements = new LinkedHashMap<>();
        elements.put(Frame.MOVE, move);
        if (money != null) {
            elements.put("money", money);
        }
        return new Frame("commerce_sell", elements);
    }

    private static Map<String, Object> eur(Number amount) {
        return Map.of("amount", amount, "currency", "EUR");
    }

    // --- N1: a price that cannot be meant ---------------------------------------------------------

    @ParameterizedTest(name = "{0} with their offer at {1} is asked about: {2}")
    @CsvSource({
            "24,    2000,  true",    // the last number that could be hours
            "24,    ,      true",    // ...and it is the NUMBER that is refused, offer or no offer
            "25,    ,      false",   // nothing to compare with: a price
            "25,    2000,  true",    // ...but far below half of theirs
            "999,   2000,  true",
            "1000,  2000,  false",   // exactly half is not below half
            "1001,  2000,  false",
            "2500,  2000,  false",   // asking more than they offer is the ordinary move
            "700,   ,      false",
    })
    void whereTheTwoThresholdsSit(double amount, Double theirOffer, boolean asked) {
        assertEquals(asked, InputSafety.implausiblePrice(frame("counter", eur(amount)), theirOffer).isPresent());
        assertEquals(asked, InputSafety.implausiblePrice(frame("propose", eur(amount)), theirOffer).isPresent(),
                "an opening offer is held to the same rule as a counter");
    }

    /** Only a move that DEMANDS a price is judged: an acceptance has none, and a constraint
     * ("nothing below 8") is not sent to the vessel at all. */
    @ParameterizedTest(name = "a {0} frame is never judged")
    @ValueSource(strings = {"accept", "reject", "ask", "constrain"})
    void onlyAProposalOrACounterIsJudged(String move) {
        assertEquals(Optional.empty(), InputSafety.implausiblePrice(frame(move, eur(8L)), 2000.0));
    }

    @Test
    void aFrameWithNoReadableAmountIsLeftAlone() {
        assertEquals(Optional.empty(), InputSafety.implausiblePrice(frame("counter", null), 2000.0));
        assertEquals(Optional.empty(), InputSafety.implausiblePrice(frame("counter", "a lot"), 2000.0));
        assertEquals(Optional.empty(),
                InputSafety.implausiblePrice(frame("counter", eur(Double.NaN)), 2000.0));
    }

    /** A bare number in the money slot (an older frame shape) is read like the object form. */
    @Test
    void aBareNumberInTheMoneySlotIsJudgedToo() {
        assertTrue(InputSafety.implausiblePrice(frame("counter", 8L), null).isPresent());
        assertFalse(InputSafety.implausiblePrice(frame("counter", 1900L), 1436.0).isPresent());
    }

    /** An offer of theirs that is not a usable number switches the comparison off - it never
     * turns every price into a question. */
    @ParameterizedTest(name = "their offer {0} compares with nothing")
    @ValueSource(doubles = {0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY})
    void anUnusableOfferOfTheirsComparesWithNothing(double theirOffer) {
        assertEquals(Optional.empty(), InputSafety.implausiblePrice(frame("counter", eur(700L)), theirOffer));
    }

    @Test
    void theHoursHintIsOnlyOfferedForANumberThatCouldBeHours() {
        assertTrue(InputSafety.implausiblePrice(frame("counter", eur(6L)), null).orElseThrow()
                .contains("hours are written with an h, beside a price: '{example}'."));
        assertFalse(InputSafety.implausiblePrice(frame("counter", eur(0L)), null).orElseThrow()
                .contains("hours are written"), "0 is not hours");
        String oneNinety = InputSafety.implausiblePrice(frame("counter", eur(1.9)), null).orElseThrow();
        assertFalse(oneNinety.contains("hours are written"), "nor is 1.9");
        assertTrue(oneNinety.startsWith("Did you mean €1.90?"), oneNinety);
    }

    @Test
    void amountsAreWrittenAsTheOfferLineWritesThem() {
        assertEquals("€1436", InputSafety.euros(1436.0));
        assertEquals("€1436.50", InputSafety.euros(1436.5));
        assertEquals("€0", InputSafety.euros(0.0));
    }

    // --- PT7: a berth that does not exist ------------------------------------------------------------

    @Test
    void aBerthOutsideOneToFourIsNamed() {
        assertEquals(Optional.of("There is no berth 5 — berths are 1 to 4 (ferries fit only berth 4)."),
                InputSafety.noSuchBerth(List.of("berth", "5")));
        assertTrue(InputSafety.noSuchBerth(List.of("2000", "at", "berth", "0")).orElseThrow()
                .startsWith("There is no berth 0"));
        assertTrue(InputSafety.noSuchBerth(List.of("berth", "07")).orElseThrow()
                .startsWith("There is no berth 7"), "the number is read as a number");
        assertTrue(InputSafety.noSuchBerth(List.of("berth", "12", "then")).orElseThrow()
                .startsWith("There is no berth 12"));
    }

    @Test
    void aBerthThatExistsOrIsNotNumberedIsNotAnswered() {
        for (String n : List.of("1", "2", "3", "4", "04")) {
            assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("at", "berth", n)), n);
        }
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("which", "berth", "is", "free")));
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("berth")));
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of()));
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("5", "berth")), "the number follows the word");
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("berth", "2.5")));
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("berth", "12345678901234567890")),
                "a number too long to be one is not parsed, and not answered");
    }

    /** Review finding: a number after the word "berth" is a berth number only if it could be one.
     * "same berth 2200" names a price, "free berth 5 hours" a stay. */
    @Test
    void aPriceOrAStayAfterTheWordIsNotABerthNumber() {
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("same", "berth", "2200")));
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("that", "berth", "250", "please")));
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("free", "berth", "5", "h")));
        assertEquals(Optional.empty(), InputSafety.noSuchBerth(List.of("any", "berth", "6", "hours")));
        assertTrue(InputSafety.noSuchBerth(List.of("free", "berth", "5", "please")).isPresent(),
                "a word that is not an hour unit does not rescue it");
    }

    @Test
    void theFirstBerthThatDoesNotExistIsTheOneNamed() {
        assertTrue(InputSafety.noSuchBerth(List.of("berth", "2", "or", "berth", "6")).orElseThrow()
                .startsWith("There is no berth 6"));
    }

    // --- N4: terms beside an acceptance or a refusal ----------------------------------------------------

    private static final PreprocessRegex PREPROCESS = new PreprocessRegex();

    @Test
    void aDecisionWithNoTermsBesideItIsNotAskedAbout() {
        assertEquals(Optional.empty(), InputSafety.termsBesideADecision(true, PREPROCESS.extract("ok then")));
        assertEquals(Optional.empty(), InputSafety.termsBesideADecision(false, PREPROCESS.extract("no way")));
        assertEquals(Optional.empty(), InputSafety.termsBesideADecision(true, PREPROCESS.extract("")));
    }

    /** Review finding: the pre-pass alone misses a bare short number, a number word and whatever
     * only Rasa extracted. Each is a term beside the decision; each is read back as it was typed. */
    @Test
    void aNumberThePrePassCannotReadIsStillATerm() {
        PreprocessRegex.Extracted nothing = PREPROCESS.extract("ok 8");
        assertEquals(Optional.empty(), InputSafety.termsBesideADecision(true, nothing),
                "setup: the pre-pass alone sees no term in 'ok 8'");

        assertTrue(InputSafety.termsBesideADecision(true, nothing, "ok 8", List.of()).orElseThrow()
                .contains("you also wrote 8 —"));
        assertTrue(InputSafety.termsBesideADecision(false, PREPROCESS.extract("no, 7.5"), "no, 7.5", List.of())
                .orElseThrow().contains("you also wrote 7.5,"), "the number as it was typed");
        assertTrue(InputSafety.termsBesideADecision(true, PREPROCESS.extract("ok two thousand"),
                        "OK, Two Thousand then", List.of()).orElseThrow()
                .contains("you also wrote \"two thousand\" —"));
        assertTrue(InputSafety.termsBesideADecision(true, PREPROCESS.extract("fine by friday"),
                        "fine by friday", List.of("friday")).orElseThrow()
                .contains("you also wrote \"friday\" —"), "a term only Rasa extracted");
    }

    @Test
    void aDecisionWithNoNumberOfAnyKindIsNotAskedAbout() {
        for (String text : List.of("ok then", "go with that one", "no way", "that's fine by us", "someone else can",
                "grand, let's go")) {
            assertEquals(Optional.empty(),
                    InputSafety.termsBesideADecision(true, PREPROCESS.extract(text), text, List.of()), text);
        }
        assertEquals(Optional.empty(), InputSafety.termsBesideADecision(true, PREPROCESS.extract("ok"),
                "ok", java.util.Arrays.asList(" ", null)), "a blank entity value is no term");
    }

    /** What the pre-pass DID read wins: it is the term in its proper form, not the raw digits. */
    @Test
    void thePrePassReadingIsPreferredOverTheRawNumber() {
        String asked = InputSafety.termsBesideADecision(true, PREPROCESS.extract("ok 2000 for 8 hours"),
                "ok 2000 for 8 hours", List.of("2000")).orElseThrow();

        assertTrue(asked.contains("you also wrote €2000 and 8h —"), asked);
    }

    @Test
    void everyTermThatWasNamedIsReadBack() {
        String all = InputSafety.termsBesideADecision(true,
                PREPROCESS.extract("ok 2000 euros for 8 hours at berth 2")).orElseThrow();
        assertTrue(all.contains("you also wrote €2000, 8h and berth 2"), all);
        assertTrue(all.endsWith("to take theirs press Accept."), all);

        String refusal = InputSafety.termsBesideADecision(false, PREPROCESS.extract("no, 1.900")).orElseThrow();
        assertTrue(refusal.contains("you also wrote €1900,"), "the thousands dot is read here too: " + refusal);
        assertTrue(refusal.endsWith("to end the negotiation press Reject."), refusal);
    }

    // --- the example slot ---------------------------------------------------------------------------------

    @Test
    void theExampleSlotTakesTheTabsExampleOrTheGenericOne() {
        assertEquals("write it like '1900 for 6h'.",
                InputSafety.fillExample("write it like '{example}'.", "1900 for 6h"));
        assertEquals("write it like 'PRICE for Nh'.", InputSafety.fillExample("write it like '{example}'.", ""));
        assertEquals("write it like 'PRICE for Nh'.", InputSafety.fillExample("write it like '{example}'.", null));
        assertEquals("no slot here", InputSafety.fillExample("no slot here", "1900 for 6h"));
        assertNull(InputSafety.fillExample(null, "1900 for 6h"));
    }

    private static int slots(String message) {
        return message.split(java.util.regex.Pattern.quote(InputSafety.EXAMPLE_SLOT), -1).length - 1;
    }

    /** Every sentence that carries the slot carries it exactly once, and the fixed ones do not. */
    @Test
    void theSlotAppearsOnceWhereAnExampleHelpsAndNowhereElse() {
        assertEquals(1, slots(InputSafety.UNREADABLE_OFFER));
        assertEquals(1, slots(InputSafety.implausiblePrice(frame("counter", eur(8L)), null).orElseThrow()));
        assertEquals(1, slots(InputSafety.implausiblePrice(frame("counter", eur(700L)), 2000.0).orElseThrow()));
        assertEquals(1, slots(InputSafety.termsBesideADecision(true, PREPROCESS.extract("ok 2000")).orElseThrow()));
        assertEquals(1, slots(InputSafety.closesAtTheirPrice(hours(8), 1436.0, false, List.of("8", "h"), "their")
                .orElseThrow()));
        assertEquals(0, slots(InputSafety.POLICY_IN_CHAT));
        assertEquals(0, slots(InputSafety.CANCEL_IN_A_NEGOTIATION));
        assertEquals(0, slots(InputSafety.noSuchBerth(List.of("berth", "5")).orElseThrow()));
    }

    // --- hours without a price (review finding 2) ---------------------------------------------------

    /** The frame the grammar builds for "8h" on a tab with only the vessel's EUR 1436 on it. */
    private static Frame hours(int hours) {
        Map<String, Object> elements = new LinkedHashMap<>();
        elements.put(Frame.MOVE, "counter");
        elements.put("money", eur(1436L));
        elements.put("duration", (long) hours);
        elements.put("berth", "berth_3");
        return new Frame("commerce_sell", elements);
    }

    @Test
    void hoursCompletedWithTheirOwnPriceAreAskedAbout() {
        assertEquals("That would close at their own price (€1436) for 8h — you gave hours but no price. "
                        + "Write both, like '{example}', or press Accept to take their offer as it stands.",
                InputSafety.closesAtTheirPrice(hours(8), 1436.0, false, List.of("8", "h"), "their").orElseThrow());
        assertTrue(InputSafety.closesAtTheirPrice(hours(8), 1436.0, false, List.of("8", "h"), "Carthago's")
                .orElseThrow().startsWith("That would close at Carthago's own price"));
    }

    /** Each way the check must NOT fire: the price was typed, the player has a bid of their own
     * (then the completion is from THEIR price), the amount is not the vessel's, there is no
     * usable offer to equal, or the frame demands nothing. */
    @Test
    void aMoveThatIsNotABorrowedPriceIsLeftAlone() {
        assertEquals(Optional.empty(),
                InputSafety.closesAtTheirPrice(hours(8), 1436.0, false, List.of("1436", "for", "8", "h"), "their"),
                "the player typed the price: they mean it");
        assertEquals(Optional.empty(),
                InputSafety.closesAtTheirPrice(hours(8), 1436.0, true, List.of("8", "h"), "their"),
                "the player has bid: hours alone change the hours of that bid");
        assertEquals(Optional.empty(),
                InputSafety.closesAtTheirPrice(hours(8), 1500.0, false, List.of("8", "h"), "their"));
        assertEquals(Optional.empty(),
                InputSafety.closesAtTheirPrice(hours(8), null, false, List.of("8", "h"), "their"));
        assertEquals(Optional.empty(),
                InputSafety.closesAtTheirPrice(frame("accept", null), 1436.0, false, List.of("deal"), "their"));
    }

    @Test
    void theOfferIsNamedAfterItsVessel() {
        assertTrue(InputSafety.implausiblePrice(frame("counter", eur(1000L)), 4500.0, "Carthago's").orElseThrow()
                .contains("less than half of Carthago's offer (€4500)"));
        assertTrue(InputSafety.implausiblePrice(frame("counter", eur(1000L)), 4500.0).orElseThrow()
                .contains("less than half of their offer (€4500)"));
    }

    @Test
    void onlyTheBareWordIsTheStatusWord() {
        for (String yes : List.of("status", "Status", "status?", "  STATUS!  ")) {
            assertTrue(InputSafety.isStatusWord(yes), yes);
        }
        for (String no : List.of("status of the tug", "what is the status", "stat", "", "statuses")) {
            assertFalse(InputSafety.isStatusWord(no), no);
        }
        assertFalse(InputSafety.isStatusWord(null));
    }
}
