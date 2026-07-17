package it.unige.portcommand.gui.model;

import java.util.List;

import it.unige.portcommand.harbourmaster.financial.ScoreRecord;
import it.unige.portcommand.lifecycle.events.GameOverEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless coverage for the game-over dialog's strings (the dialog itself cannot run in the
 * test lanes — HeadlessException; the EndOfDaySummaryModel precedent). */
class GameOverModelTest {

    private static GameOverEvent event(String reason) {
        return new GameOverEvent(reason, 152_400.0, 30, 82);
    }

    @Test
    void reasonLinesAreHumanReadable() {
        assertTrue(new GameOverModel(event(GameOverEvent.REASON_BANKRUPT), List.of())
                .reasonLine().contains("Bankrupt"));
        assertTrue(new GameOverModel(event(GameOverEvent.REASON_DAY_CAP), List.of())
                .reasonLine().contains("Day cap"));
        assertTrue(new GameOverModel(event(GameOverEvent.REASON_QUIT), List.of())
                .reasonLine().contains("resigned"));
        assertEquals("weird_future_reason",
                new GameOverModel(event("weird_future_reason"), List.of()).reasonLine(),
                "an unknown reason falls back to the raw string, never throws");
    }

    @Test
    void linesCarryTheFinalStatsLocaleIndependent() {
        List<String> lines = new GameOverModel(event(GameOverEvent.REASON_DAY_CAP), List.of()).lines();
        assertTrue(lines.stream().anyMatch(l -> l.contains("€152,400")), "Locale.ROOT grouping");
        assertTrue(lines.stream().anyMatch(l -> l.contains("Final reputation:  82")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("(no recorded runs)")),
                "an empty leaderboard renders a placeholder, not nothing");
    }

    @Test
    void thisRunIsMarkedInTheTop5() {
        ScoreRecord thisRun = new ScoreRecord(152_400.0, 30, 82.0, "2026-07-17");
        ScoreRecord other = new ScoreRecord(90_000.0, 30, 60.0, "2026-07-01");
        List<String> lines = new GameOverModel(event(GameOverEvent.REASON_DAY_CAP),
                List.of(thisRun, other)).lines();

        assertTrue(lines.stream().anyMatch(l -> l.contains("1. €152,400") && l.contains("← this run")));
        assertTrue(lines.stream().noneMatch(l -> l.contains("€90,000") && l.contains("← this run")));
    }

    @Test
    void titleNamesTheFinalDay() {
        assertEquals("Game Over — Day 30",
                new GameOverModel(event(GameOverEvent.REASON_QUIT), List.of()).title());
    }
}
