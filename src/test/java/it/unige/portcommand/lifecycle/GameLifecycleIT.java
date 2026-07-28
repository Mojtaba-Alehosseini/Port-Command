package it.unige.portcommand.lifecycle;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.EndOfDayCompletedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.TugJobAwardedEvent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.events.AutosaveRequestedEvent;
import it.unige.portcommand.lifecycle.events.DayRolloverEvent;
import it.unige.portcommand.lifecycle.events.GameOverEvent;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task-24 capstone: a full multi-day headless run — live tick stream, three real
 * day rollovers with settled summaries, pause semantics, and the 3-consecutive-day
 * bankruptcy game-over — against the REAL boot stack ({@code JadeBootstrap} + the
 * full {@code AgentRoster}, so the HarbourMaster's own detect behaviour and
 * coordinator do the work).
 *
 * <p>Determinism: the {@link WallClockAdvancer} is deliberately NOT running (the
 * lifecycle gets a never-firing one-hour advancer through the package-private
 * seam) — the test drives {@code SimClock.advance}/{@code advanceToNextDay}
 * directly, the established IT pattern since task 06. Waits are bounded polls on
 * the bus audit log ({@code EventBusProbe}), the documented no-Awaitility
 * exception (INVARIANTS, task 11).
 */
@Tag("integration")
class GameLifecycleIT {

    private static final int TEST_PORT = 18099;

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void threeDaysRollBankruptcyEndsTheRunAndPauseFreezesEverything() throws Exception {
        boot = new JadeBootstrap();
        boot.start(new BootstrapConfig(TEST_PORT, false, "realtime", 300));
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                boot.getLLMBridge(), boot.getEventBus());
        SimClock clock = boot.getSimClock();

        // Main-parity wiring (Main constructs these around the boot; JadeBootstrap does not).
        GameLifecycle lifecycle = new GameLifecycle(clock, boot.getEventBus(),
                new WallClockAdvancer(clock, 3_600_000L));
        Leaderboard leaderboard = new Leaderboard(tempDir.resolve("scores.json"));
        new GameOverGuard(boot.getEventBus(), lifecycle, leaderboard,
                AgentRoster.STARTING_WALLET, AgentRoster.STARTING_REPUTATION);
        lifecycle.startNewGame();
        assertEquals(GameMode.RUNNING, lifecycle.mode());

        // --- M1: the tick stream is live once time moves ---
        clock.advance(1_000); // 4.8 sim-minutes
        SimClockTickEvent tick = awaitEvent(SimClockTickEvent.class, t -> t.simMillis() > 0, 5_000);
        assertNotNull(tick, "SimClockTickEvent must flow once sim time advances");
        assertEquals(1, tick.gameDay());

        // --- M2: three clean day rollovers, settled in order, exactly once each ---
        for (int day = 1; day <= 3; day++) {
            clock.advanceToNextDay();
            int endedDay = day;
            EndOfDayCompletedEvent settled = awaitEvent(EndOfDayCompletedEvent.class,
                    e -> e.summary().gameDay() == endedDay, 10_000);
            assertNotNull(settled, "day " + day + " must settle");
            DayRolloverEvent rollover = awaitEvent(DayRolloverEvent.class,
                    e -> e.summary().gameDay() == endedDay, 10_000);
            assertNotNull(rollover, "day " + day + " must roll over after settling");
            assertEquals(day + 1, rollover.newDay());
            assertNotNull(awaitEvent(AutosaveRequestedEvent.class, e -> e.day() == endedDay, 10_000),
                    "day " + day + " must request an autosave (task 22's hook)");
        }
        assertEquals(3, countEvents(EndOfDayCompletedEvent.class), "exactly one settlement per day");
        // 50,000 − 3 × 5,200: no deals in this run, so the fixed cost is the whole story.
        double walletAfterThreeDays = AgentRoster.STARTING_WALLET - 3 * 5_200.0;
        EndOfDayCompletedEvent day3 = awaitEvent(EndOfDayCompletedEvent.class,
                e -> e.summary().gameDay() == 3, 1_000);
        assertEquals(walletAfterThreeDays, day3.summary().endingWallet(), 0.0001);

        // --- Pause: wall time keeps passing, sim time and rollovers do not ---
        lifecycle.pause();
        long frozenAt = clock.nowSimMillis();
        long rolloverCountAtPause = countEvents(DayRolloverEvent.class);
        clock.advance(60_000); // would be ~4.8 sim-hours — must be swallowed while paused
        long deadline = System.currentTimeMillis() + 600;
        while (System.currentTimeMillis() < deadline) {
            assertEquals(frozenAt, clock.nowSimMillis(), "paused sim time must not move");
            Thread.sleep(20); // bounded observation window (class javadoc)
        }
        assertEquals(rolloverCountAtPause, countEvents(DayRolloverEvent.class),
                "no rollover can fire while paused");
        lifecycle.resume();

        // --- Bankruptcy: deep debt held for 3 CONSECUTIVE settled days ---
        // A synthetic overspend (planning/24's "synthetic deal that overspends"): the ledger
        // coordinator charges the awarded tug cost, exactly as a real award would.
        boot.getEventBus().publish(new TugJobAwardedEvent("V-SYNTH", "tug_1", 80_000.0,
                "cnp-synthetic-overspend", clock.nowSimMillis()));
        assertTrue(countEvents(GameOverEvent.class) == 0,
                "mid-day deep debt alone must NOT end the game (bankruptcy is EOD-only)");

        for (int day = 4; day <= 6; day++) {
            clock.advanceToNextDay();
            int endedDay = day;
            assertNotNull(awaitEvent(EndOfDayCompletedEvent.class,
                    e -> e.summary().gameDay() == endedDay, 10_000), "day " + day + " must settle");
        }

        GameOverEvent gameOver = awaitEvent(GameOverEvent.class, e -> true, 10_000);
        assertNotNull(gameOver, "3 consecutive deep-debt settlements must end the run");
        assertEquals(GameOverEvent.REASON_BANKRUPT, gameOver.reason());
        assertEquals(6, gameOver.finalDay());
        assertEquals(1, countEvents(GameOverEvent.class), "exactly one game-over per run");
        assertEquals(GameMode.GAME_OVER, lifecycle.mode());
        assertTrue(clock.isPaused(), "game over freezes the clock");
        assertEquals(1, leaderboard.top5().size(), "the run was recorded");

        // The world is over: another midnight jump must settle nothing new.
        long settledCount = countEvents(EndOfDayCompletedEvent.class);
        clock.advanceToNextDay(); // advanceToNextDay bypasses pause by design (test/scenario tool)
        Thread.sleep(600); // bounded observation window — detect ticks ~3×/625ms here
        assertEquals(settledCount + 1, countEvents(EndOfDayCompletedEvent.class),
                "the detect+settle pipeline itself still runs; the GUARD is what fired once");
        assertEquals(1, countEvents(GameOverEvent.class), "still exactly one game-over");
    }

    private <T extends Event> T awaitEvent(Class<T> type, Predicate<T> match, long totalMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + totalMillis;
        while (System.currentTimeMillis() < deadline) {
            List<T> hits = EventBusProbe.published(boot.getEventBus()).stream()
                    .filter(type::isInstance).map(type::cast).filter(match).toList();
            if (!hits.isEmpty()) {
                return hits.get(hits.size() - 1);
            }
            Thread.sleep(20); // bounded poll (class javadoc)
        }
        return null;
    }

    private long countEvents(Class<? extends Event> type) {
        return EventBusProbe.published(boot.getEventBus()).stream().filter(type::isInstance).count();
    }
}
