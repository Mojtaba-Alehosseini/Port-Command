package it.unige.portcommand.persistence;

/**
 * A manual save was requested while a walk-in negotiation is live (planning/22 Step 22.7:
 * a mid-negotiation snapshot has no clean phase boundary). The GUI catches this and shows
 * a polite dialog; the game keeps running. The EOD autosave never throws this — it drops
 * negotiating vessels from the snapshot instead (decision dated 2026-07-17, planning/22).
 */
public class SaveNotAllowedException extends Exception {

    public SaveNotAllowedException(String message) {
        super(message);
    }
}
