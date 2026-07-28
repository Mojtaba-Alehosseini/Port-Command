package it.unige.portcommand.persistence;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.harbourmaster.financial.ScoreRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 22's ONLY leaderboard test: a Jackson round-trip on a {@link ScoreRecord} list —
 * {@code Leaderboard.java} (top-5 trimming, high-score insertion, file I/O) is task 20's,
 * tested in {@code LeaderboardTest} (planning/22 hard constraint / CANONICAL_FINDINGS fix 5).
 */
class LeaderboardSerializationTest {

    @Test
    void scoreRecordListRoundTripsThroughJacksonWithSnakeCaseKeys() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ScoreRecord> original = List.of(
                new ScoreRecord(61_450.0, 12, 84, "2026-07-15"),
                new ScoreRecord(48_200.0, 30, 71, "2026-07-16"));

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"final_wallet\""), "wire keys are snake_case (task-20 convention)");
        assertTrue(json.contains("\"recorded_on\""));

        List<ScoreRecord> reloaded = mapper.readValue(json, new TypeReference<List<ScoreRecord>>() { });
        assertEquals(original, reloaded);
    }
}
