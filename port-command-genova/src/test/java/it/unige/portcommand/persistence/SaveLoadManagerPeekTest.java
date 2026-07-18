package it.unige.portcommand.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.unige.portcommand.persistence.SaveLoadManager.SaveSlotInfo;
import it.unige.portcommand.util.SimClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SaveLoadManager#peek(Path)} reads only a slot's headline fields (day / wallet / save time)
 * off the JSON tree, for the task-21 SaveLoadDialog preview — deliberately WITHOUT binding a whole
 * {@link GameState} (a real load rebuilds the world). The fixtures here are intentionally partial
 * JSON, which would fail a full {@code load()} but must still peek cleanly.
 */
class SaveLoadManagerPeekTest {

    @Test
    void peekReadsDayWalletAndSavedAtFromAPartialTree(@TempDir Path dir) throws Exception {
        Path slot = dir.resolve("savegame.json");
        long simMillis = 2L * 86_400_000L; // day 3
        Files.writeString(slot, "{\"schemaVersion\":1,\"savedAt\":\"2026-07-18T10:30:00Z\","
                + "\"simClock\":{\"simMillis\":" + simMillis + ",\"realSecondsPerGameDay\":300},"
                + "\"wallet\":44800.0}");

        SaveSlotInfo info = new SaveLoadManager(dir).peek(slot).orElseThrow();
        assertEquals(SimClock.gameDayOf(simMillis), info.day());
        assertEquals(44800.0, info.wallet(), 0.0001);
        assertEquals("2026-07-18T10:30:00Z", info.savedAt());
    }

    @Test
    void peekIsEmptyForAMissingSlot(@TempDir Path dir) {
        assertTrue(new SaveLoadManager(dir).peek(dir.resolve("nope.json")).isEmpty());
    }

    @Test
    void peekIsEmptyForAWrongSchemaVersion(@TempDir Path dir) throws Exception {
        Path slot = dir.resolve("savegame.json");
        Files.writeString(slot, "{\"schemaVersion\":999,\"wallet\":1.0,\"simClock\":{\"simMillis\":0}}");
        assertTrue(new SaveLoadManager(dir).peek(slot).isEmpty());
    }

    @Test
    void peekIsEmptyForUnreadableJson(@TempDir Path dir) throws Exception {
        Path slot = dir.resolve("savegame.json");
        Files.writeString(slot, "not json at all {{{");
        Optional<SaveSlotInfo> info = new SaveLoadManager(dir).peek(slot);
        assertTrue(info.isEmpty());
    }

    @Test
    void peekToleratesAMissingSavedAtField(@TempDir Path dir) throws Exception {
        Path slot = dir.resolve("autosave.json");
        Files.writeString(slot, "{\"schemaVersion\":1,\"simClock\":{\"simMillis\":0},\"wallet\":5000.0}");
        SaveSlotInfo info = new SaveLoadManager(dir).peek(slot).orElseThrow();
        assertEquals(1, info.day());
        assertEquals(5000.0, info.wallet(), 0.0001);
        assertEquals(null, info.savedAt());
    }
}
