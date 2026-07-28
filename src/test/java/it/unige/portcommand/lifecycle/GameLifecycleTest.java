package it.unige.portcommand.lifecycle;

import it.unige.portcommand.lifecycle.events.GamePausedEvent;
import it.unige.portcommand.lifecycle.events.GameResumedEvent;
import it.unige.portcommand.lifecycle.events.QuitEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLifecycleTest {

    private SimClock clock;
    private EventBus bus;
    private WallClockAdvancer advancer;
    private GameLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        clock = new SimClock(300);
        bus = new EventBus();
        // A one-hour tick period: the advancer never actually fires during a unit test,
        // so lifecycle state assertions stay deterministic (the ticking itself is
        // WallClockAdvancerTest's subject).
        advancer = new WallClockAdvancer(clock, 3_600_000L);
        lifecycle = new GameLifecycle(clock, bus, advancer);
    }

    // ---- transition matrix ----

    @Test
    void transitionMatrixMatchesPlanning() {
        assertTrue(GameMode.canTransition(GameMode.MENU, GameMode.LOADING));
        assertTrue(GameMode.canTransition(GameMode.LOADING, GameMode.RUNNING));
        assertTrue(GameMode.canTransition(GameMode.LOADING, GameMode.MENU), "failed-load edge (May audit)");
        assertTrue(GameMode.canTransition(GameMode.RUNNING, GameMode.PAUSED));
        assertTrue(GameMode.canTransition(GameMode.RUNNING, GameMode.GAME_OVER));
        assertTrue(GameMode.canTransition(GameMode.PAUSED, GameMode.RUNNING));
        assertTrue(GameMode.canTransition(GameMode.PAUSED, GameMode.GAME_OVER));
        assertTrue(GameMode.canTransition(GameMode.GAME_OVER, GameMode.MENU));

        assertFalse(GameMode.canTransition(GameMode.MENU, GameMode.RUNNING), "must pass through LOADING");
        assertFalse(GameMode.canTransition(GameMode.MENU, GameMode.GAME_OVER));
        assertFalse(GameMode.canTransition(GameMode.RUNNING, GameMode.MENU));
        assertFalse(GameMode.canTransition(GameMode.GAME_OVER, GameMode.RUNNING));
        assertFalse(GameMode.canTransition(GameMode.PAUSED, GameMode.PAUSED), "self-loops are illegal");
    }

    // ---- lifecycle ----

    @Test
    void startNewGameReachesRunningWithALiveClockAndAdvancer() {
        lifecycle.startNewGame();

        assertEquals(GameMode.RUNNING, lifecycle.mode());
        assertTrue(lifecycle.isRunning());
        assertFalse(clock.isPaused());
        assertTrue(advancer.isRunning());
    }

    @Test
    void pauseFreezesTheClockAndPublishes() {
        lifecycle.startNewGame();
        lifecycle.pause();

        assertEquals(GameMode.PAUSED, lifecycle.mode());
        assertTrue(clock.isPaused());
        assertEquals(1, EventBusProbe.published(bus).stream()
                .filter(GamePausedEvent.class::isInstance).count());
    }

    @Test
    void resumeUnfreezesAndPublishes() {
        lifecycle.startNewGame();
        lifecycle.pause();
        lifecycle.resume();

        assertEquals(GameMode.RUNNING, lifecycle.mode());
        assertFalse(clock.isPaused());
        assertEquals(1, EventBusProbe.published(bus).stream()
                .filter(GameResumedEvent.class::isInstance).count());
    }

    @Test
    void quitOnlyPublishesTheQuitEventAndLeavesTheModeAlone() {
        lifecycle.startNewGame();
        lifecycle.quit();

        assertEquals(GameMode.RUNNING, lifecycle.mode(),
                "the GAME_OVER transition belongs to GameOverGuard, not quit()");
        assertEquals(1, EventBusProbe.published(bus).stream()
                .filter(QuitEvent.class::isInstance).count());
    }

    @Test
    void gameOverFreezesEverythingFromRunning() {
        lifecycle.startNewGame();
        lifecycle.gameOver();

        assertEquals(GameMode.GAME_OVER, lifecycle.mode());
        assertTrue(clock.isPaused());
        assertFalse(advancer.isRunning());
    }

    @Test
    void gameOverIsAlsoLegalFromPaused() {
        lifecycle.startNewGame();
        lifecycle.pause();
        lifecycle.gameOver();

        assertEquals(GameMode.GAME_OVER, lifecycle.mode());
    }

    @Test
    void pauseIfRunningAndResumeIfPausedAreTolerantOfEveryMode() {
        assertFalse(lifecycle.pauseIfRunning(), "MENU: no-op");
        lifecycle.startNewGame();
        assertTrue(lifecycle.pauseIfRunning());
        assertEquals(GameMode.PAUSED, lifecycle.mode());
        assertFalse(lifecycle.pauseIfRunning(), "already paused: no-op, no double event");
        lifecycle.resumeIfPaused();
        assertEquals(GameMode.RUNNING, lifecycle.mode());
        lifecycle.resumeIfPaused(); // RUNNING: no-op
        assertEquals(GameMode.RUNNING, lifecycle.mode());
        lifecycle.gameOver();
        assertFalse(lifecycle.pauseIfRunning(), "GAME_OVER: no-op — a plain pause() would throw");
        lifecycle.resumeIfPaused();
        assertEquals(GameMode.GAME_OVER, lifecycle.mode(), "a finished run can never resume");
    }

    @Test
    void illegalTransitionsThrowWithAClearMessage() {
        IllegalStateException fromMenu = assertThrows(IllegalStateException.class, lifecycle::gameOver);
        assertTrue(fromMenu.getMessage().contains("MENU"));

        lifecycle.startNewGame();
        assertThrows(IllegalStateException.class, lifecycle::resume, "resume while RUNNING");
        lifecycle.pause();
        assertThrows(IllegalStateException.class, lifecycle::pause, "double pause");
    }
}
