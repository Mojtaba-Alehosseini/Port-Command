package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;

import it.unige.portcommand.ontology.Deal;

/**
 * Pure model of the left column's "Finished" list (S18b, playtest 2 PT5): one line per negotiation
 * that has ended in this game, newest first — the vessel's name, and how it ended ("deal · €1,700",
 * "left", "turned away"). No Swing/AWT import; a view only draws the rows. A click on a line shows
 * that negotiation's transcript again, read-only.
 *
 * <p>Before S18b a closed negotiation's tab left the strip 30 s after it closed and its transcript
 * was gone for good. The list is session-only: it is not saved, and a load or a new game starts it
 * empty (the negotiations it lists belong to the world that was replaced).
 */
public final class FinishedListModel {

    /**
     * One line of the list.
     *
     * @param dialogueId the dialogue a click shows
     * @param vesselName what the line calls the vessel
     * @param result     how it ended, in one or two words: {@code deal · €1,700}, {@code left},
     *                   {@code turned away}
     * @param deal       whether it ended in a deal (the view draws the others muted)
     */
    public record Row(String dialogueId, String vesselName, String result, boolean deal) {
    }

    static final String DEAL = "deal";
    static final String LEFT = "left";
    static final String TURNED_AWAY = "turned away";

    private FinishedListModel() {
    }

    /** @param finished the finished negotiations, newest first (the directory's order is kept) */
    public static List<Row> rows(List<FinishedDialogue> finished) {
        if (finished == null || finished.isEmpty()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>(finished.size());
        for (FinishedDialogue f : finished) {
            if (f != null) {
                rows.add(new Row(f.dialogueId(), f.vesselName(), result(f), f.outcome() == Deal.Outcome.DEAL));
            }
        }
        return List.copyOf(rows);
    }

    /** {@code deal · €1,700} (just {@code deal} when the close named no price), {@code left} for
     * every way a vessel walks away, {@code turned away} when the player sent it off. */
    static String result(FinishedDialogue f) {
        return switch (f.outcome()) {
            case DEAL -> f.price() > 0 ? DEAL + OfferLine.DOT + Euros.of(f.price()) : DEAL;
            case PLAYER_REFUSED -> TURNED_AWAY;
            case WITHDRAW_PRICE, WITHDRAW_DURATION, TIMEOUT -> LEFT;
        };
    }
}
