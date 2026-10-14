package it.unige.portcommand.gui.model;

import java.util.Locale;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.ontology.Deal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S18b (playtest 2, PT3; UI tree U3): the plain lines a walk-in tab gets from the negotiation, and
 * the chat's way of writing an amount. Playtest 2: "Vessel replies are hard to read".
 */
class OutcomeLinesTest {

    /** The vessel's reply: {@code vesselOffer} for {@code hours}, the HarbourMaster's record of the
     * player's price stamped in ({@code 0.0} = none). */
    private static WalkInDialogueSnapshot reply(double vesselOffer, int hours, double playerOffer, String berthId) {
        return new WalkInDialogueSnapshot("W001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", berthId,
                hours, vesselOffer, playerOffer, 2, 0.0, false, "Santa Lucia");
    }

    @Test
    void theOpeningOfferNamesItsPriceHoursAndBerth() {
        assertEquals("They offer €1,460 for 5h at berth 2.", OutcomeLines.opening(reply(1460.0, 5, 0.0, "berth_2")));
        assertEquals("They offer €1,460 for 5h.", OutcomeLines.opening(reply(1460.0, 5, 0.0, " ")),
                "no berth known: the line says nothing about one");
    }

    @Test
    void aCounterIsTheirPriceAndHours() {
        assertEquals("They counter: €1,460 for 5h.", OutcomeLines.counter(reply(1460.0, 5, 2000.0, "berth_2"), 5));
        assertEquals("They counter: €1,460 for 5h.", OutcomeLines.counter(reply(1460.0, 5, 0.0, "berth_2"), null),
                "before the player named any hours there is nothing to push back on");
    }

    /** Task 19b's floor pushback, kept: the vessel's hours came back above the player's. */
    @Test
    void aCounterAboveThePlayersHoursSaysTheyNeedMore() {
        assertEquals("They counter: €1,460 for 6h — they need at least 6h.",
                OutcomeLines.counter(reply(1460.0, 6, 2000.0, "berth_2"), 4));
    }

    /** PT3's "They accept €1,600 but need at least 6h.": the player's price, only the hours pushed back. */
    @Test
    void aCounterAtThePlayersOwnPriceSaysTheyAcceptItButNeedMoreHours() {
        assertEquals("They accept €1,600 but need at least 6h.",
                OutcomeLines.counter(reply(1600.0, 6, 1600.0, "berth_2"), 4));
        assertEquals("They accept €1,600 but need at least 6h.",
                OutcomeLines.counter(reply(1600.4, 6, 1599.6, "berth_2"), 4), "the same whole euro");
        assertEquals("They counter: €1,600 for 6h.", OutcomeLines.counter(reply(1600.0, 6, 1600.0, "berth_2"), 6),
                "the same hours too: not an acceptance with a condition");
    }

    @Test
    void aDealSaysWhoAcceptedWhatAndThatItIsDone() {
        assertEquals("✔ They accepted €1,700 for 6h — deal done.",
                OutcomeLines.closing(Deal.Outcome.DEAL, 1700.0, 6, true));
        assertEquals("✔ Deal done: €1,460 for 5h.", OutcomeLines.closing(Deal.Outcome.DEAL, 1460.0, 5, false),
                "their own offer, accepted on the player's side (by the player or the Assistant): nobody named");
        assertEquals("✔ Deal done.", OutcomeLines.closing(Deal.Outcome.DEAL, 0.0, 0, false),
                "a close that named no terms claims none");
    }

    @Test
    void everyOtherEndingSaysTheyLeftAndWhy() {
        assertEquals("✖ They left (price too high).", OutcomeLines.closing(Deal.Outcome.WITHDRAW_PRICE, 0, 0, false));
        assertEquals("✖ They left (you never granted the hours they need).",
                OutcomeLines.closing(Deal.Outcome.WITHDRAW_DURATION, 0, 0, false));
        assertEquals("✖ They left (negotiation timed out).", OutcomeLines.closing(Deal.Outcome.TIMEOUT, 0, 0, false));
        assertEquals("✖ You turned them away.", OutcomeLines.closing(Deal.Outcome.PLAYER_REFUSED, 0, 0, false));
    }

    @Test
    void theChatWritesWholeEurosWithAThousandsComma() {
        assertEquals("€1,460", Euros.of(1460.0));
        assertEquals("€1,460", Euros.of(1459.6), "rounded to the whole euro");
        assertEquals("€15,000", Euros.of(15_000.0));
        assertEquals("€950", Euros.of(950.0));
        assertEquals("−€1,183", Euros.of(-1183.0));
        assertEquals("€0", Euros.of(-0.4), "never −€0 for a few cents under zero");
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY); // would group as 1.460
            assertEquals("€1,460", Euros.of(1460.0), "the same on every machine");
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }
}
