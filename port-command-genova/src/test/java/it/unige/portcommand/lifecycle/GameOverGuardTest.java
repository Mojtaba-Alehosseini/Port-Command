package it.unige.portcommand.lifecycle;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.harbourmaster.financial.EndOfDaySummary;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.events.DayRolloverEvent;
import it.unige.portcommand.lifecycle.events.GameOverEvent;
import it.unige.portcommand.lifecycle.events.QuitEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameOverGuardTest {

    @TempDir
    Path tempDir;

    private EventBus bus;
    private GameLifecycle lifecycle;
    private Leaderboard leaderboard;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        SimClock clock = new SimClock(300);
        lifecycle = new GameLifecycle(clock, bus, new WallClockAdvancer(clock, 3_600_000L));
        leaderboard = new Leaderboard(tempDir.resolve("scores.json"));
        new GameOverGuard(bus, lifecycle, leaderboard, 50_000.0, 70.0);
        lifecycle.startNewGame();
    }

    private static EndOfDaySummary summary(int day, double endingWallet) {
        return new EndOfDaySummary(day, 0.0, 5_200.0, endingWallet, 50, Map.of());
    }

    private void endDayAt(int day, double endingWallet) {
        bus.publish(new EndOfDayCompletedEvent(summary(day, endingWallet)));
    }

    private List<GameOverEvent> gameOvers() {
        return EventBusProbe.published(bus).stream()
                .filter(GameOverEvent.class::isInstance)
                .map(GameOverEvent.class::cast)
                .toList();
    }

    // ---- bankruptcy (wallet < −€20,000 at EOD, 3 consecutive days — ADR-14) ----

    @Test
    void threeConsecutiveDeepDebtDaysEndTheRunExactlyOnce() {
        endDayAt(1, -25_000.0);
        endDayAt(2, -26_000.0);
        assertTrue(gameOvers().isEmpty(), "two deep days are not yet bankruptcy");

        endDayAt(3, -27_000.0);

        List<GameOverEvent> events = gameOvers();
        assertEquals(1, events.size());
        assertEquals(GameOverEvent.REASON_BANKRUPT, events.get(0).reason());
        assertEquals(-27_000.0, events.get(0).finalWallet());
        assertEquals(3, events.get(0).finalDay());
        assertEquals(GameMode.GAME_OVER, lifecycle.mode());
        assertEquals(1, leaderboard.top5().size(), "the run was recorded before the event");
    }

    @Test
    void aRecoveredDayResetsTheConsecutiveStreak() {
        endDayAt(1, -25_000.0);
        endDayAt(2, -25_000.0);
        endDayAt(3, -19_999.0); // above the −20,000 floor — streak broken
        endDayAt(4, -25_000.0);
        endDayAt(5, -25_000.0);
        assertTrue(gameOvers().isEmpty(), "streak restarted at day 4 — only 2 consecutive so far");

        endDayAt(6, -25_000.0);
        assertEquals(1, gameOvers().size());
    }

    @Test
    void aSkippedDayRestartsTheStreak() {
        // "Consecutive" means adjacent game DAYS, not merely successive events (review n1).
        endDayAt(1, -25_000.0);
        endDayAt(3, -25_000.0); // day 2 never settled — the streak restarts at day 3
        endDayAt(4, -25_000.0);
        assertTrue(gameOvers().isEmpty(), "days 3,4 are only 2 adjacent deep days");

        endDayAt(5, -25_000.0);
        assertEquals(1, gameOvers().size());
    }

    @Test
    void exactlyTheFloorIsNotBelowIt() {
        endDayAt(1, -20_000.0);
        endDayAt(2, -20_000.0);
        endDayAt(3, -20_000.0);
        assertTrue(gameOvers().isEmpty(), "the rule is strictly below the floor");
    }

    @Test
    void midDayDebtAloneNeverEndsTheGame() {
        bus.publish(new WalletChangedEvent(-90_000.0, -90_000.0, "tug_job", "V1", 1_000L));
        assertTrue(gameOvers().isEmpty(), "bankruptcy is decided at EOD only");
    }

    // ---- day cap ----

    @Test
    void dayPastTheCapEndsTheRun() {
        bus.publish(new DayRolloverEvent(31, summary(30, 12_345.0)));

        List<GameOverEvent> events = gameOvers();
        assertEquals(1, events.size());
        assertEquals(GameOverEvent.REASON_DAY_CAP, events.get(0).reason());
        assertEquals(30, events.get(0).finalDay(), "the run ended ON the capped day");
        assertEquals(12_345.0, events.get(0).finalWallet());
    }

    @Test
    void dayThirtyItselfIsStillPlayable() {
        bus.publish(new DayRolloverEvent(30, summary(29, 1_000.0)));
        assertTrue(gameOvers().isEmpty());
    }

    // ---- quit ----

    @Test
    void quitUsesTheLatestAbsoluteFiguresFromTheBus() {
        bus.publish(new WalletChangedEvent(12_345.0, 100.0, "berth_base", "V1", 1_000L));
        bus.publish(new DayRolloverEvent(2, summary(1, 12_345.0)));
        bus.publish(new QuitEvent());

        List<GameOverEvent> events = gameOvers();
        assertEquals(1, events.size());
        assertEquals(GameOverEvent.REASON_QUIT, events.get(0).reason());
        assertEquals(12_345.0, events.get(0).finalWallet());
        assertEquals(2, events.get(0).finalDay());
    }

    @Test
    void quitBeforeAnyMoneyEventReportsTheStartingFigures() {
        bus.publish(new QuitEvent());

        GameOverEvent event = gameOvers().get(0);
        assertEquals(50_000.0, event.finalWallet());
        assertEquals(70, event.finalReputation());
        assertEquals(1, event.finalDay());
    }

    // ---- single-fire ----

    @Test
    void backToBackTriggersFireExactlyOneGameOver() {
        endDayAt(1, -25_000.0);
        endDayAt(2, -25_000.0);
        endDayAt(3, -25_000.0); // bankrupt fires
        bus.publish(new QuitEvent());
        bus.publish(new DayRolloverEvent(31, summary(30, 0.0)));

        assertEquals(1, gameOvers().size());
        assertEquals(GameOverEvent.REASON_BANKRUPT, gameOvers().get(0).reason());
        assertEquals(1, leaderboard.top5().size(), "one run, one leaderboard record");
    }

    @Test
    void firesCleanlyFromPausedToo() {
        lifecycle.pause();
        bus.publish(new QuitEvent());

        assertEquals(GameMode.GAME_OVER, lifecycle.mode());
        assertEquals(1, gameOvers().size());
    }
}
