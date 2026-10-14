package it.unige.portcommand.gui;

import it.unige.portcommand.lifecycle.GameLifecycle;

/**
 * S18b (REMAINING_AREAS_DECISIONS P2): the pause a dialog holds while it is open. "The clock (and
 * patience) keeps running behind Help, Settings, Save/Load and New Game." Taken when the dialog
 * opens: it pauses a RUNNING game and leaves any other mode alone.
 *
 * <p>{@link #release()} resumes the game only if THIS pause stopped it, only once, and only in the
 * world it was taken in — a game the player had paused before opening the dialog stays paused, and
 * a world that a load or a new game put up meanwhile decides for itself whether it runs. (S18b
 * adversarial review: Help, open under an end-of-day report that had started the Tutorial, resumed
 * the new world behind its first card; a {@code forget()} the load and the new game called at
 * their start left the old game paused when the save file or the scenario then failed its checks.)
 * So a dialog that may replace the world releases its pause once the outcome is known. A save
 * hands its pause to the save, which releases it when the file is written (the save's quiesce IS
 * that pause, audit C-08).
 *
 * <p>EDT only, like the window that holds it.
 */
final class DialogPause {

    private final GameLifecycle lifecycle;
    /** The world this pause was taken in ({@link GameLifecycle#worldGeneration}). */
    private final long world;
    private boolean held;

    DialogPause(GameLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.world = lifecycle.worldGeneration();
        this.held = lifecycle.pauseIfRunning();
    }

    /** Puts the game back as it was when the dialog opened — if it is still the same game. */
    void release() {
        if (held) {
            held = false;
            if (lifecycle.worldGeneration() == world) {
                lifecycle.resumeIfPaused();
            }
        }
    }

    /** Whether this pause stopped the game and has not let it go yet. */
    boolean held() {
        return held;
    }
}
