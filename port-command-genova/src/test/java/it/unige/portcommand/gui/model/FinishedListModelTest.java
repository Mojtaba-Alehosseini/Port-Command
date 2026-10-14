package it.unige.portcommand.gui.model;

import java.util.Arrays;
import java.util.List;

import it.unige.portcommand.ontology.Deal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S18b (playtest 2, PT5): the left column's "Finished" list — one line per ended negotiation, the
 * vessel's name and how it ended. Playtest 2: "Closed negotiations disappear; I want to review them".
 */
class FinishedListModelTest {

    @Test
    void eachLineIsTheVesselAndHowItEnded() {
        List<FinishedListModel.Row> rows = FinishedListModel.rows(List.of(
                new FinishedDialogue("nego-W003", "Tirreno", Deal.Outcome.PLAYER_REFUSED, 0, 0),
                new FinishedDialogue("nego-W002", "Carthago", Deal.Outcome.TIMEOUT, 0, 0),
                new FinishedDialogue("nego-W001", "Genoa Star", Deal.Outcome.DEAL, 1700.0, 6)));

        assertEquals(List.of(
                new FinishedListModel.Row("nego-W003", "Tirreno", "turned away", false),
                new FinishedListModel.Row("nego-W002", "Carthago", "left", false),
                new FinishedListModel.Row("nego-W001", "Genoa Star", "deal · €1,700", true)), rows,
                "the directory's order (newest first) is kept");
    }

    @Test
    void everyWayAVesselWalksAwayReadsLeft() {
        for (Deal.Outcome outcome : List.of(Deal.Outcome.WITHDRAW_PRICE, Deal.Outcome.WITHDRAW_DURATION,
                Deal.Outcome.TIMEOUT)) {
            assertEquals("left", FinishedListModel.result(new FinishedDialogue("d", "V", outcome, 0, 0)), outcome.name());
        }
    }

    @Test
    void aDealWithoutTermsSaysOnlyDeal() {
        assertEquals("deal", FinishedListModel.result(new FinishedDialogue("d", "V", Deal.Outcome.DEAL, 0, 0)));
    }

    @Test
    void aNamelessVesselIsCalledByItsDialogue() {
        assertEquals("nego-W009", new FinishedDialogue("nego-W009", " ", Deal.Outcome.TIMEOUT, 0, 0).vesselName());
    }

    @Test
    void nothingFinishedIsNoLines() {
        assertEquals(List.of(), FinishedListModel.rows(List.of()));
        assertEquals(List.of(), FinishedListModel.rows(null));
        assertEquals(1, FinishedListModel.rows(Arrays.asList(null,
                new FinishedDialogue("d", "V", Deal.Outcome.TIMEOUT, 0, 0))).size(), "a null entry is skipped");
    }
}
