package it.unige.portcommand.gui.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import it.unige.portcommand.persistence.SaveLoadManager.SaveSlotInfo;
import it.unige.portcommand.persistence.SaveLoadManager.SlotState;

/**
 * Pure text of one slot's line in the Save/Load dialog (S18b, REMAINING_AREAS_DECISIONS P6 and
 * P9). No Swing/AWT import; {@code SaveLoadDialog} only puts it in a label.
 *
 * <ul>
 *   <li>P6: a slot whose file cannot be read says so and why —
 *       {@code Unreadable save (damaged file)}. It used to read "— empty —" like a slot with
 *       no file at all.</li>
 *   <li>P9: the save time is the player's own clock time, not UTC; and the autosave's row names
 *       the day that ENDED — the autosave is written just after midnight, so its clock is
 *       already on the next day, and the row used to say "Day 3" for the save of Day 2.</li>
 * </ul>
 */
public final class SaveSlotText {

    static final String EMPTY = "— empty —";
    static final String SEPARATOR = "  ·  ";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private SaveSlotText() {
    }

    /**
     * @param autosave whether this is the end-of-day slot
     * @param zone     the player's time zone ({@link ZoneId#systemDefault()} in the dialog)
     * @return {@code Day 3  ·  €50,000  ·  2026-10-14 17:05}, {@code End of Day 2  ·  …},
     *         {@code Unreadable save (damaged file)} or {@code — empty —}
     */
    public static String preview(SlotState state, boolean autosave, ZoneId zone) {
        return switch (state) {
            case SlotState.Empty empty -> EMPTY;
            case SlotState.Unreadable unreadable -> "Unreadable save (" + unreadable.reason() + ")";
            case SlotState.Readable readable -> readableLine(readable.info(), autosave, zone);
        };
    }

    private static String readableLine(SaveSlotInfo info, boolean autosave, ZoneId zone) {
        String day = autosave ? "End of Day " + Math.max(1, info.day() - 1) : "Day " + info.day();
        String when = info.savedAt() == null ? "" : SEPARATOR + localTime(info.savedAt(), zone);
        return day + SEPARATOR + Euros.of(info.wallet()) + when;
    }

    /** ISO-8601 instant in the player's zone, {@code yyyy-MM-dd HH:mm}; text that is not an instant
     * falls back to the pre-S18b cut of the raw text (never throws in the render path). */
    static String localTime(String iso, ZoneId zone) {
        try {
            return WHEN.format(Instant.parse(iso).atZone(zone));
        } catch (DateTimeParseException notAnInstant) {
            String t = iso.replace('T', ' ');
            return t.length() >= 16 ? t.substring(0, 16) : t;
        }
    }
}
