package it.unige.portcommand.gui.model;

/**
 * One negotiation that is still open, as the rest of the window sees it (S15): enough to list it
 * ("Waiting for you"), to select its tab, and to follow its vessel in the comm log.
 *
 * @param dialogueId the tab's dialogue id ({@code nego-<vesselId>})
 * @param vesselId   the vessel's raw id
 * @param vesselName the vessel's display name (its id when it has none)
 */
public record OpenDialogue(String dialogueId, String vesselId, String vesselName) {
}
