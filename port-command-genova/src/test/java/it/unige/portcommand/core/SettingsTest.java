package it.unige.portcommand.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14 (2026-10-11, playability/timing): the shipped defaults and the new {@code gameSpeed}
 * setting. The record is what the save file's settings block is written from and bound to, so
 * the JSON cases here are the save-compatibility contract (schemaVersion stays 1).
 */
class SettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theShippedDefaultsAreSixRoundsASixtyMinuteDayAndTimesOne() {
        Settings defaults = Settings.defaults();

        assertEquals(6, defaults.roundLimit(), "default rounds went 4 -> 6 after the live playtest");
        assertEquals(3_600L, defaults.realSecondsPerGameDay(), "a sandbox game day is 60 real minutes");
        assertEquals(1, defaults.gameSpeed());
    }

    @Test
    void defaultsJsonShipsExactlyTheHardCodedFallback() {
        // Two sources for one set of numbers: they must not drift (a "Reset to defaults" in the
        // Settings form reads the record, a fresh game reads the file).
        assertEquals(Settings.defaults(), Settings.classpathDefaults());
    }

    @Test
    void theRoundLimitRangeIsStillTwoToEight() {
        assertEquals(2, Settings.defaults().withRoundLimit(2).roundLimit());
        assertEquals(8, Settings.defaults().withRoundLimit(8).roundLimit());
        assertThrows(IllegalArgumentException.class, () -> Settings.defaults().withRoundLimit(1));
        assertThrows(IllegalArgumentException.class, () -> Settings.defaults().withRoundLimit(9));
    }

    @Test
    void gameSpeedIsOneOfOneTwoFour() {
        assertEquals(java.util.List.of(1, 2, 4), Settings.GAME_SPEEDS);
        for (int speed : Settings.GAME_SPEEDS) {
            assertEquals(speed, Settings.defaults().withGameSpeed(speed).gameSpeed());
        }
        assertThrows(IllegalArgumentException.class, () -> Settings.defaults().withGameSpeed(3));
        assertThrows(IllegalArgumentException.class, () -> Settings.defaults().withGameSpeed(8));
        assertThrows(IllegalArgumentException.class, () -> Settings.defaults().withGameSpeed(-1));
    }

    @Test
    void theSpeedSurvivesTheJsonRoundTripTheSaveFileUses() throws Exception {
        Settings saved = Settings.defaults().withGameSpeed(4).withRoundLimit(7);

        String json = JSON.writeValueAsString(saved);
        JsonNode node = JSON.readTree(json);

        assertEquals(4, node.get("gameSpeed").asInt(), "the settings block carries the speed");
        assertFalse(node.has("timeLimitSeconds"), "the retired nominal window is no longer written");
        assertEquals(saved, JSON.readValue(json, Settings.class));
    }

    @Test
    void aSaveFromBeforeTheSpeedControlLoadsAtTimesOne() throws Exception {
        // A pre-S14 settings block: it still has timeLimitSeconds and has no gameSpeed. Both must
        // be tolerated — the retired key ignored, the missing one read as "it was running at x1".
        String preS14 = """
                {"roundLimit":4,"timeLimitSeconds":60,"autopilotEnabled":true,"difficulty":"HARD",
                 "realSecondsPerGameDay":300,"maxGameDays":30,"firstLaunch":false,
                 "llmModel":"microsoft/Phi-4-mini-instruct","withdrawalPenaltyEur":500.0,
                 "cnpReplyBySimSeconds":5,"cnpRetryAfterSimSeconds":30,"dailyTargetEur":5000.0,
                 "bankruptcyFloorEur":-20000.0}
                """;

        Settings loaded = JSON.readValue(preS14, Settings.class);

        assertEquals(1, loaded.gameSpeed());
        assertEquals(4, loaded.roundLimit(), "an old save keeps the round limit it was saved with");
        assertEquals(300L, loaded.realSecondsPerGameDay(), "...and its own day length");
        assertTrue(loaded.autopilotEnabled());
        assertEquals("HARD", loaded.difficulty());
    }

    @Test
    void everyWitherCarriesTheSpeedAlong() {
        // The with-ers rebuild the record positionally; a field added last is the one a wither
        // most easily drops back to its default.
        Settings fast = Settings.defaults().withGameSpeed(4);

        assertEquals(4, fast.withRoundLimit(5).gameSpeed());
        assertEquals(4, fast.withAutopilotEnabled(true).gameSpeed());
        assertEquals(4, fast.withDifficulty("EASY").gameSpeed());
        assertEquals(4, fast.withRealSecondsPerGameDay(900).gameSpeed());
        assertEquals(4, fast.withLlmModel("google/gemma-3-4b-it").gameSpeed());
    }
}
