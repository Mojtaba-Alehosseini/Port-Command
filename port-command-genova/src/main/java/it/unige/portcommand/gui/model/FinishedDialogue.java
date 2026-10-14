package it.unige.portcommand.gui.model;

import java.util.Objects;

import it.unige.portcommand.ontology.Deal;

/**
 * S18b (playtest 2, PT5): one negotiation that has ended in this game — what the left column's
 * "Finished" list shows a line for, and the dialogue a click on that line brings back.
 *
 * <p>P-04: built from the snapshot's vessel name and the close's own terms only.
 *
 * @param dialogueId the dialogue whose transcript a click shows
 * @param vesselName what the line calls the vessel (its id when it has no name)
 * @param outcome    how it ended
 * @param price      the agreed price of a deal; 0 for any other outcome, or a deal that named none
 * @param hours      the agreed stay of a deal; 0 otherwise
 */
public record FinishedDialogue(String dialogueId, String vesselName, Deal.Outcome outcome, double price, int hours) {

    public FinishedDialogue {
        Objects.requireNonNull(dialogueId, "dialogueId");
        Objects.requireNonNull(outcome, "outcome");
        vesselName = vesselName == null || vesselName.isBlank() ? dialogueId : vesselName;
    }
}
