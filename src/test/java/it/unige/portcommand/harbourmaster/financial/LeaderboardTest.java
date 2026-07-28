package it.unige.portcommand.harbourmaster.financial;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 17);
    private static final double EPS = 1e-9;
    private static final int MAX = Leaderboard.MAX_ENTRIES;

    @TempDir
    Path tempDir;

    private Path scoresFile() {
        return tempDir.resolve("save").resolve("scores.json");
    }

    @Test
    void anAbsentFileLoadsAsAnEmptyBoard() {
        assertEquals(List.of(), new Leaderboard(scoresFile()).top5());
    }

    @Test
    void recordingWritesTheFileAndCreatesItsParentDirectory() {
        Leaderboard board = new Leaderboard(scoresFile());

        assertTrue(board.recordIfHighScore(10_000.0, 5, 60.0, DATE));

        assertTrue(Files.exists(scoresFile()), "save/scores.json was created");
        assertEquals(1, board.top5().size());
    }

    @Test
    void roundTripsThroughTheFile() {
        new Leaderboard(scoresFile()).recordIfHighScore(12_345.67, 7, 83.0, DATE);

        List<ScoreRecord> reloaded = new Leaderboard(scoresFile()).top5();

        assertEquals(1, reloaded.size());
        ScoreRecord entry = reloaded.get(0);
        assertEquals(12_345.67, entry.finalWallet(), EPS);
        assertEquals(7, entry.finalDay());
        assertEquals(83.0, entry.finalReputation(), EPS);
        assertEquals("2026-07-17", entry.recordedOn());
    }

    @Test
    void usesSnakeCaseWireKeys() throws Exception {
        new Leaderboard(scoresFile()).recordIfHighScore(10_000.0, 5, 60.0, DATE);

        String json = Files.readString(scoresFile());

        assertTrue(json.contains("\"final_wallet\""), json);
        assertTrue(json.contains("\"final_day\""), json);
        assertTrue(json.contains("\"final_reputation\""), json);
        assertTrue(json.contains("\"recorded_on\""), json);
    }

    @Test
    void ranksByWalletDescending() {
        Leaderboard board = new Leaderboard(scoresFile());
        board.recordIfHighScore(5_000.0, 3, 50.0, DATE);
        board.recordIfHighScore(15_000.0, 9, 70.0, DATE);
        board.recordIfHighScore(10_000.0, 6, 60.0, DATE);

        assertEquals(List.of(15_000.0, 10_000.0, 5_000.0),
                board.top5().stream().map(ScoreRecord::finalWallet).toList());
    }

    @Test
    void keepsOnlyTheTopFiveAndDropsTheLoser() {
        Leaderboard board = new Leaderboard(scoresFile());
        for (int i = 1; i <= 5; i++) {
            board.recordIfHighScore(i * 1_000.0, i, 50.0, DATE);
        }

        assertFalse(board.recordIfHighScore(500.0, 2, 50.0, DATE), "worse than every entry");

        assertEquals(5, board.top5().size());
        assertEquals(List.of(5_000.0, 4_000.0, 3_000.0, 2_000.0, 1_000.0),
                board.top5().stream().map(ScoreRecord::finalWallet).toList());
    }

    @Test
    void aNewBestDisplacesTheWorstEntry() {
        Leaderboard board = new Leaderboard(scoresFile());
        for (int i = 1; i <= 5; i++) {
            board.recordIfHighScore(i * 1_000.0, i, 50.0, DATE);
        }

        assertTrue(board.recordIfHighScore(9_999.0, 8, 90.0, DATE));

        assertEquals(List.of(9_999.0, 5_000.0, 4_000.0, 3_000.0, 2_000.0),
                board.top5().stream().map(ScoreRecord::finalWallet).toList());
        assertEquals(5, board.top5().size());
    }

    /** Equal wallets: the player who got there in fewer days ranks higher. */
    @Test
    void tiesOnWalletAreBrokenByTheEarlierDay() {
        Leaderboard board = new Leaderboard(scoresFile());
        board.recordIfHighScore(10_000.0, 9, 50.0, DATE);
        board.recordIfHighScore(10_000.0, 3, 50.0, DATE);

        assertEquals(List.of(3, 9), board.top5().stream().map(ScoreRecord::finalDay).toList());
    }

    /**
     * The ordering must be TOTAL, or two equal runs could swap places across a reload and make
     * task 22's round-trip test flaky.
     */
    @Test
    void orderingIsStableAcrossAReload() {
        Leaderboard board = new Leaderboard(scoresFile());
        board.recordIfHighScore(10_000.0, 5, 50.0, DATE);
        board.recordIfHighScore(10_000.0, 5, 70.0, DATE.plusDays(1));
        board.recordIfHighScore(10_000.0, 5, 60.0, DATE.minusDays(1));

        List<ScoreRecord> first = board.top5();
        List<ScoreRecord> reloaded = new Leaderboard(scoresFile()).top5();

        assertEquals(first, reloaded);
    }

    /**
     * The return value must describe THIS candidate, not an equal-looking neighbour. A record's
     * {@code indexOf} matches by value, so a 6th identical score would report "you made the board"
     * while being truncated away. (Adversarial-review finding; both variants reproduced.)
     */
    @Test
    void aCandidateTruncatedAwayReportsThatItMissed() {
        Leaderboard board = new Leaderboard(scoresFile());
        for (int i = 0; i < MAX; i++) {
            board.recordIfHighScore(10_000.0, 5, 50.0, DATE);
        }

        assertFalse(board.recordIfHighScore(10_000.0, 5, 50.0, DATE),
                "a 6th identical score is dropped — it must not claim it made the top 5");
        assertEquals(MAX, board.top5().size());
    }

    @Test
    void aCandidateTyingTheWorstEntryExactlyReportsThatItMissed() {
        Leaderboard board = new Leaderboard(scoresFile());
        for (int i = 1; i <= MAX; i++) {
            board.recordIfHighScore(i * 1_000.0, i, 50.0, DATE);
        }

        assertFalse(board.recordIfHighScore(1_000.0, 1, 50.0, DATE),
                "ties the worst entry and loses on the stable sort — the pre-existing entry survives");
        assertEquals(MAX, board.top5().size());
    }

    @Test
    void aCorruptFileLoadsAsAnEmptyBoardRatherThanThrowing() throws Exception {
        Files.createDirectories(scoresFile().getParent());
        Files.writeString(scoresFile(), "{ this is not a score list");

        Leaderboard board = new Leaderboard(scoresFile());

        assertEquals(List.of(), board.top5());
        assertTrue(board.recordIfHighScore(1_000.0, 1, 50.0, DATE), "and recovers to a usable board");
    }

    @Test
    void anOversizedFileIsTruncatedToTheTopFiveOnLoad() throws Exception {
        Leaderboard writer = new Leaderboard(scoresFile());
        for (int i = 1; i <= 5; i++) {
            writer.recordIfHighScore(i * 1_000.0, i, 50.0, DATE);
        }
        // Hand-write a 7-entry file, as a corrupted or hand-edited board might be.
        String json = Files.readString(scoresFile())
                .replaceFirst("\\[", "[ {\"final_wallet\":9.0,\"final_day\":1,"
                        + "\"final_reputation\":1.0,\"recorded_on\":\"2026-01-01\"},"
                        + "{\"final_wallet\":8.0,\"final_day\":1,"
                        + "\"final_reputation\":1.0,\"recorded_on\":\"2026-01-01\"},");
        Files.writeString(scoresFile(), json);

        assertEquals(5, new Leaderboard(scoresFile()).top5().size());
    }
}
