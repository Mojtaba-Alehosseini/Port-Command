package it.unige.portcommand.lifecycle;

import java.nio.file.Path;
import java.util.LinkedHashMap;

import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.events.GameOverEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 22: {@link GameOverGuard#restoreProgress} — the bankruptcy streak survives a load. */
class GameOverGuardRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void aRestoredTwoDayStreakEndsTheRunOnTheThirdAdjacentDeepDebtDay() {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(300);
        GameLifecycle lifecycle = new GameLifecycle(clock, bus, new WallClockAdvancer(clock, 3_600_000L));
        lifecycle.startNewGame();
        GameOverGuard guard = new GameOverGuard(bus, lifecycle, new Leaderboard(tempDir.resolve("s.json")),
                -25_000.0, 40.0);
        guard.restoreProgress(7, 2, 6); // save taken after days 5+6 ended deep in debt

        guard.onEndOfDayCompleted(new EndOfDayCompletedEvent(deepDebtSummary(7)));
        assertEquals(1, EventBusProbe.published(bus).stream()
                        .filter(GameOverEvent.class::isInstance).count(),
                "day 7 is the third consecutive deep-debt day — the restored streak counts");
        guard.close();
    }

    @Test
    void aRestoredStreakResetsWhenTheNextDayRecovers() {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(300);
        GameLifecycle lifecycle = new GameLifecycle(clock, bus, new WallClockAdvancer(clock, 3_600_000L));
        lifecycle.startNewGame();
        GameOverGuard guard = new GameOverGuard(bus, lifecycle, new Leaderboard(tempDir.resolve("s.json")),
                -25_000.0, 40.0);
        guard.restoreProgress(7, 2, 6);

        guard.onEndOfDayCompleted(new EndOfDayCompletedEvent(recoveredSummary(7)));
        guard.onEndOfDayCompleted(new EndOfDayCompletedEvent(deepDebtSummary(8)));
        assertEquals(0, EventBusProbe.published(bus).stream()
                        .filter(GameOverEvent.class::isInstance).count(),
                "a recovered day resets the streak; day 8 restarts at 1 of 3");
        guard.close();
    }

    private static EndOfDaySummary deepDebtSummary(int day) {
        return new EndOfDaySummary(day, 0.0, 5_200.0, -25_000.0, 30, new LinkedHashMap<>());
    }

    private static EndOfDaySummary recoveredSummary(int day) {
        return new EndOfDaySummary(day, 9_000.0, 5_200.0, -1_000.0, 45, new LinkedHashMap<>());
    }
}
