package it.unige.portcommand.persistence.events;

import it.unige.portcommand.util.Event;

/**
 * A save file was loaded and the rebuilt world is live (task 22). Published AFTER the
 * fresh container + agents are up and the ledgers/clock/artifacts are restored, BEFORE
 * the clock resumes. GUI consumers use it to drop stale live-play state (open chat tabs
 * belong to the torn-down world; the loader also publishes synthetic wallet/reputation/
 * clock refresh events so the HUD shows restored figures immediately — no "—"
 * placeholders after a load).
 *
 * @param gameDay    restored 1-based game day
 * @param wallet     restored wallet balance (€)
 * @param reputation restored reputation (int, 0..100 — the project-wide canonical type)
 */
public record GameLoadedEvent(int gameDay, double wallet, int reputation) implements Event {
}
