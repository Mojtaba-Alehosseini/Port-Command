package it.unige.portcommand.assistant;

import java.util.List;
import java.util.Optional;

import it.unige.portcommand.artifacts.MarketStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17a (ADR-21, playtest PT13; tree-analysis A3, A4): the Assistant's own words — the plain
 * sentence a Hint opens with, the details line under it, the autopilot's line — and the
 * {@link HintCard} that carries a recommendation to the tab. Every expected text is a literal:
 * these are the strings the player reads, and the point of PT13 is what they say.
 *
 * <p>Pure: recommendations are built by hand, so nothing here depends on the negotiation math
 * (which is frozen) or on Prolog.
 */
class AssistantWordingTest {

    private static WalkInDialogueSnapshot cargo(int hours, double offer, int round) {
        return new WalkInDialogueSnapshot("v1", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo",
                "berth_1", hours, offer, 0.0, round, 0.0, false);
    }

    private static Recommendation rec(String action, double price, double pAccept, double ev, String status) {
        return new Recommendation(action, price, pAccept, ev, List.of("R7"), MarketStats.empty(),
                "cargo_vessel", "berth_1", 6, status, null);
    }

    private static final Recommendation ACCEPT =
            rec(RecommendationCandidate.ACCEPT, 1529.0, 1.0, 429.0, "compatible");
    private static final Recommendation COUNTER =
            rec(RecommendationCandidate.COUNTER, 1579.6, 0.6249, 639.7, "compatible");
    private static final Recommendation NO_PAY =
            rec(RecommendationCandidate.REJECT_SILENT, 0.0, 0.0, 0.0, "compatible");
    private static final Recommendation NO_FIT =
            rec(RecommendationCandidate.REJECT_SILENT, 0.0, 0.0, 0.0, "incompatible");

    // ---- the sentence (PT13) -------------------------------------------------------------------

    @Test
    void theHintSaysWhatToDoInTheWordsTheButtonsUse() {
        assertEquals("Accept their offer of €1529 for 6h.", AssistantWording.hintText(ACCEPT, cargo(6, 1529.0, 1)));
        assertEquals("Counter at €1580 for 6h.", AssistantWording.hintText(COUNTER, cargo(6, 1436.0, 1)));
    }

    @Test
    void aNoThatDoesNotPaySaysSoInPlainWords() {
        assertEquals("Say no — costs (berth time, tugs, customs) are higher than this offer.",
                AssistantWording.hintText(NO_PAY, cargo(17, 1477.0, 1)));
    }

    @Test
    void aNoBecauseOfTheBerthNamesTheBerthThePlayersWay() {
        assertEquals("Say no — this ship does not fit berth 1.", AssistantWording.hintText(NO_FIT, cargo(6, 1529.0, 1)));
    }

    @Test
    void theSentenceCarriesNoneOfThePlaytestsJargon() {
        // PT13 quoted the old first line: "Recommended reject silently for cargo_vessel at berth_1
        // (17h). ... Acceptance probability 0%, expected value EUR 0. Berth berth_1 is compatible."
        for (Recommendation rec : List.of(ACCEPT, COUNTER, NO_PAY, NO_FIT)) {
            String text = AssistantWording.hintText(rec, cargo(17, 1477.0, 1)).toLowerCase(java.util.Locale.ROOT);
            for (String jargon : List.of("silent", "berth_1", "cargo_vessel", "expected value", "probability",
                    "ev ", "recommended", "r7", "%")) {
                assertFalse(text.contains(jargon), "'" + jargon + "' in: " + text);
            }
        }
    }

    // ---- the details line (A4) -----------------------------------------------------------------

    @Test
    void theDetailsLineHoldsTheNumbersAndTheBerthCheck() {
        assertEquals("EV €429 · chance 100% · R7 ✓",
                AssistantWording.details(ACCEPT, cargo(6, 1529.0, 1), Optional.empty()));
        assertEquals("EV €640 · chance 62% · R7 ✓",
                AssistantWording.details(COUNTER, cargo(6, 1436.0, 1), Optional.empty()));
    }

    @Test
    void aNoThatDoesNotPayShowsTheOfferAgainstTheCosts() {
        // 2 tugs x 350 + 100 customs + 17 h x 50 = 1650: the algorithm's own marginal cost.
        assertEquals("offer €1477 · costs €1650 · R7 ✓",
                AssistantWording.details(NO_PAY, cargo(17, 1477.0, 1), Optional.empty()));
    }

    @Test
    void aNoBecauseOfTheBerthShowsOnlyTheFailedCheck() {
        assertEquals("R7 ✗", AssistantWording.details(NO_FIT, cargo(6, 1529.0, 1), Optional.empty()));
    }

    @Test
    void theWeatherWaitClosesTheDetailsLineWhenThereIsOne() {
        assertEquals("EV €429 · chance 100% · R7 ✓ · ship will wait: wind 32 kn [R15]",
                AssistantWording.details(ACCEPT, cargo(6, 1529.0, 1),
                        Optional.of("ship will wait: wind 32 kn [R15]")));
        assertEquals("R7 ✗ · ship will wait: sea state stormy [R19]",
                AssistantWording.details(NO_FIT, cargo(6, 1529.0, 1),
                        Optional.of("ship will wait: sea state stormy [R19]")));
        assertEquals("EV €429 · chance 100% · R7 ✓",
                AssistantWording.details(ACCEPT, cargo(6, 1529.0, 1), Optional.of("  ")), "a blank part adds nothing");
    }

    // ---- the card (A3) -------------------------------------------------------------------------

    @Test
    void theCardCarriesTheRecommendationAndTheOfferItAnswered() {
        WalkInDialogueSnapshot asked = cargo(6, 1436.0, 2);
        HintCard card = HintCard.of(COUNTER, asked, "EV €640 · chance 62% · R7 ✓");

        assertEquals(RecommendationCandidate.COUNTER, card.action());
        assertEquals(1579.6, card.price(), 0.0);
        assertEquals(1580L, card.shownPrice(), "what every text shows, and what \"Use this\" sends");
        assertEquals(6, card.hours());
        assertEquals(2, card.round());
        assertEquals(1436.0, card.vesselOffer(), 0.0);
        assertEquals("counter at €1580 for 6h", card.suggestion());
        assertEquals("EV €640 · chance 62% · R7 ✓", card.details());
        assertTrue(card.isCounter());
        assertFalse(card.isAccept());
        assertFalse(card.isSayNo());
    }

    @Test
    void theSuggestionIsTheRecommendationInAFewWords() {
        assertEquals("accept €1529 for 6h", HintCard.of(ACCEPT, cargo(6, 1529.0, 1), "").suggestion());
        assertEquals("say no", HintCard.of(NO_PAY, cargo(17, 1477.0, 1), "").suggestion());
        assertTrue(HintCard.of(ACCEPT, cargo(6, 1529.0, 1), "").isAccept());
        assertTrue(HintCard.of(NO_FIT, cargo(6, 1529.0, 1), "").isSayNo());
    }

    @Test
    void aCardDescribesItsOfferUntilTheVesselMoves() {
        HintCard card = HintCard.of(ACCEPT, cargo(6, 1529.0, 1), "");

        assertTrue(card.describes(cargo(6, 1529.0, 1)), "the same round, the same offer: still good");
        assertTrue(card.describes(cargo(6, 1529.0, 1).withLastPlayerOffer(1700.0)),
                "what the PLAYER last asked does not make the advice stale");
        assertFalse(card.describes(cargo(6, 1480.0, 2)), "she countered: accepting 'their offer' would take a "
                + "price the Assistant never looked at");
        assertFalse(card.describes(cargo(6, 1480.0, 1)), "a different offer is a different question");
        assertFalse(card.describes(cargo(6, 1529.0, 2)), "and so is a later round");
        assertFalse(card.describes(null), "a tab with no offer on it has nothing to use the card on");
    }

    // ---- the autopilot's line (A9) -------------------------------------------------------------

    @Test
    void theAutopilotSaysWhatItDidInThePastTense() {
        String p = "🤖 Assistant (autopilot): ";
        assertEquals(p + "accepted their offer of €1529.",
                AssistantWording.autopilotMove(RecommendationCandidate.ACCEPT, 1529.0, null, ACCEPT));
        assertEquals(p + "countered at €1580.",
                AssistantWording.autopilotMove(RecommendationCandidate.COUNTER, 1579.6, null, COUNTER));
        assertEquals(p + "said no — costs (berth time, tugs, customs) are higher than this offer.",
                AssistantWording.autopilotMove(RecommendationCandidate.REJECT_SILENT, 0.0, null, NO_PAY));
        assertEquals(p + "said no — this ship does not fit berth 1.",
                AssistantWording.autopilotMove(RecommendationCandidate.REJECT_SILENT, 0.0, null, NO_FIT));
    }

    @Test
    void aPolicysMoveNamesThePolicy() {
        String p = "🤖 Assistant (autopilot): ";
        String policy = "auto accept if price > 6000 for tankers";
        assertEquals(p + "accepted their offer of €6200 (your policy: " + policy + ").",
                AssistantWording.autopilotMove(RecommendationCandidate.ACCEPT, 6200.0, policy, null));
        assertEquals(p + "countered at €6900 (your policy: auto counter +15% if price > 0).",
                AssistantWording.autopilotMove(RecommendationCandidate.COUNTER, 6900.0,
                        "auto counter +15% if price > 0", null));
        assertEquals(p + "said no (your policy: auto reject if price < 1500).",
                AssistantWording.autopilotMove(RecommendationCandidate.REJECT_SILENT, 0.0,
                        "auto reject if price < 1500", null));
        assertEquals(p + "said no — the port's rules refused the move.", AssistantWording.autopilotGaveUp());
    }

    @Test
    void moneyIsWholeEurosWithNoSeparatorOnEveryMachine() {
        assertEquals("€1529", AssistantWording.euros(1529.0));
        assertEquals("€1580", AssistantWording.euros(1579.6));
        assertEquals("€12400", AssistantWording.euros(12_400.0));
        assertEquals("€0", AssistantWording.euros(0.0));
    }
}
