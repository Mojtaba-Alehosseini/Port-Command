package it.unige.portcommand.gui;

import it.unige.portcommand.lifecycle.GameLifecycle;
import it.unige.portcommand.lifecycle.GameMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S18b (REMAINING_AREAS_DECISIONS P2): "The clock (and patience) keeps running behind Help,
 * Settings, Save/Load and New Game." The pause a dialog holds — against a real {@link GameLifecycle}.
 */
class DialogPauseTest {

    private GameLifecycle lifecycle;

    @BeforeEach
    void aRunningGame() {
        lifecycle = new GameLifecycle(new SimClock(300), new EventBus());
        lifecycle.startNewGame();
        assertEquals(GameMode.RUNNING, lifecycle.mode(), "setup");
    }

    @AfterEach
    void endTheGame() {
        lifecycle.gameOver(); // stops the lifecycle's own wall-clock advancer
    }

    @Test
    void aRunningGamePausesWhileTheDialogIsOpenAndRunsAgainAfter() {
        DialogPause pause = new DialogPause(lifecycle);
        assertEquals(GameMode.PAUSED, lifecycle.mode(), "the dialog is open: the game waits");
        assertTrue(pause.held());

        pause.release();
        assertEquals(GameMode.RUNNING, lifecycle.mode(), "closed: the game goes on as it was");
    }

    @Test
    void aGameThePlayerPausedStaysPaused() {
        lifecycle.pause();
        DialogPause pause = new DialogPause(lifecycle);
        assertFalse(pause.held(), "this pause stopped nothing");

        pause.release();
        assertEquals(GameMode.PAUSED, lifecycle.mode(), "the player's own pause is theirs to end");
    }

    @Test
    void releasingTwiceResumesOnce() {
        DialogPause pause = new DialogPause(lifecycle);
        pause.release();
        lifecycle.pause(); // the player pauses after the dialog closed

        pause.release();
        assertEquals(GameMode.PAUSED, lifecycle.mode(), "a second release must not undo the player's pause");
    }

    /** S18b (adversarial review): a pause is never released into another world. Help, open under
     * an end-of-day report that started the Tutorial (which waits, paused - PT2), resumed the new
     * world behind its first card when it closed. */
    @Test
    void aPauseWhoseWorldIsReplacedPutsNothingBack() {
        DialogPause pause = new DialogPause(lifecycle);
        assertTrue(pause.held());
        lifecycle.beginLoading(); // a load or a new game replaces the world...
        lifecycle.completeLoading();
        lifecycle.pause(); // ...which comes up paused, as the window starts and loads one (PT2, P8)

        pause.release();
        assertEquals(GameMode.PAUSED, lifecycle.mode(), "the new world decides for itself whether it runs");
    }

    /** S18b (adversarial review): a load that fails before its teardown leaves the world as it was,
     * and the Load dialog's pause goes back to it - the game runs on (P2). */
    @Test
    void aWorldThatStayedGetsItsPauseBack() {
        DialogPause pause = new DialogPause(lifecycle);
        // the save file failed its checks: no teardown, no new world

        pause.release();
        assertEquals(GameMode.RUNNING, lifecycle.mode());
    }
}
