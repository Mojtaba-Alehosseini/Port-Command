package it.unige.portcommand.gui.model;

import java.time.ZoneId;

import it.unige.portcommand.persistence.SaveLoadManager.SaveSlotInfo;
import it.unige.portcommand.persistence.SaveLoadManager.SlotState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S18b (REMAINING_AREAS_DECISIONS P6, P9): one slot's line in the Save/Load dialog. P6: "Corrupt /
 * future-version saves show as '— empty —'." P9: "Save slot shows UTC time; autosave row says
 * 'Day N+1'."
 */
class SaveSlotTextTest {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    @Test
    void aSaveShowsItsDayWalletAndTheLocalTime() {
        SlotState saved = new SlotState.Readable(new SaveSlotInfo(3, 50_000.0, "2026-10-14T15:05:00Z"));

        assertEquals("Day 3  ·  €50,000  ·  2026-10-14 17:05", SaveSlotText.preview(saved, false, ROME),
                "15:05 UTC is 17:05 in Genoa");
    }

    @Test
    void theAutosaveNamesTheDayThatEnded() {
        // Written just after midnight: the clock already reads day 3 for the save of day 2.
        SlotState autosave = new SlotState.Readable(new SaveSlotInfo(3, 41_200.0, null));

        assertEquals("End of Day 2  ·  €41,200", SaveSlotText.preview(autosave, true, ROME));
    }

    @Test
    void aFileThatCannotBeReadSaysSoAndWhy() {
        assertEquals("Unreadable save (damaged file)",
                SaveSlotText.preview(new SlotState.Unreadable("damaged file"), false, ROME));
    }

    @Test
    void anEmptySlotIsStillEmpty() {
        assertEquals("— empty —", SaveSlotText.preview(new SlotState.Empty(), true, ROME));
    }

    @Test
    void aSaveTimeThatIsNotAnInstantIsShownAsWritten() {
        assertEquals("yesterday", SaveSlotText.localTime("yesterday", ROME));
        assertEquals("2026-10-14 15:05", SaveSlotText.localTime("2026-10-14T15:05:00.123", ROME),
                "no zone in it: the pre-S18b cut of the text");
    }
}
