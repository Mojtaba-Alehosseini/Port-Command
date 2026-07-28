package it.unige.portcommand.lifecycle;

import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 22: the LOADING edges added for save/load (incl. load-after-game-over). */
class GameLifecycleLoadingTest {

    private GameLifecycle lifecycle(SimClock clock, EventBus bus) {
        return new GameLifecycle(clock, bus, new WallClockAdvancer(clock, 3_600_000L));
    }

    @Test
    void beginCompleteLoadingFreezesThenResumes() {
        SimClock clock = new SimClock(300);
        GameLifecycle lifecycle = lifecycle(clock, new EventBus());
        lifecycle.startNewGame();

        lifecycle.beginLoading();
        assertEquals(GameMode.LOADING, lifecycle.mode());
        assertTrue(clock.isPaused(), "nothing sim-driven may move during the swap");

        lifecycle.completeLoading();
        assertEquals(GameMode.RUNNING, lifecycle.mode());
        assertFalse(clock.isPaused());
    }

    @Test
    void loadingIsLegalFromPausedAndFromGameOver() {
        SimClock clock = new SimClock(300);
        GameLifecycle lifecycle = lifecycle(clock, new EventBus());
        lifecycle.startNewGame();
        lifecycle.pause();
        lifecycle.beginLoading();
        lifecycle.completeLoading();

        lifecycle.gameOver();
        lifecycle.beginLoading(); // continue-from-autosave after the run ended
        lifecycle.completeLoading();
        assertEquals(GameMode.RUNNING, lifecycle.mode());
        assertFalse(clock.isPaused());
    }

    @Test
    void failedLoadLandsInMenuAndANewGameIsStillPossible() {
        SimClock clock = new SimClock(300);
        GameLifecycle lifecycle = lifecycle(clock, new EventBus());
        lifecycle.startNewGame();
        lifecycle.beginLoading();

        lifecycle.failLoading();
        assertEquals(GameMode.MENU, lifecycle.mode());
        assertTrue(clock.isPaused());

        lifecycle.startNewGame(); // MENU → LOADING → RUNNING still works after the failure
        assertEquals(GameMode.RUNNING, lifecycle.mode());
    }

    @Test
    void loadingFromMenuDirectlyIsLegalButFromLoadingIsNot() {
        SimClock clock = new SimClock(300);
        GameLifecycle lifecycle = lifecycle(clock, new EventBus());
        lifecycle.beginLoading(); // MENU → LOADING (the --load cold start)
        assertEquals(GameMode.LOADING, lifecycle.mode());
        assertThrows(IllegalStateException.class, lifecycle::beginLoading);
    }
}
