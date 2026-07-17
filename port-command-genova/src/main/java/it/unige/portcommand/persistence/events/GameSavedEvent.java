package it.unige.portcommand.persistence.events;

import it.unige.portcommand.util.Event;

/**
 * A save (manual or autosave) was written successfully (task 22). Carries where and how
 * big, so the notification strip can report "Saved — savegame.json (87 KB)" without
 * re-statting the file.
 *
 * @param path        the file written (relative {@code save/...} path, as configured)
 * @param bytes       bytes written
 * @param autosave    {@code true} for the end-of-day autosave, {@code false} for Game → Save
 * @param gameDay     the in-progress game day at the moment of the save
 */
public record GameSavedEvent(String path, long bytes, boolean autosave, int gameDay) implements Event {
}
