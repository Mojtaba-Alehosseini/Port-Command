package it.unige.portcommand.gui;

import it.unige.portcommand.lifecycle.GameLifecycle;

/**
 * S18b (REMAINING_AREAS_DECISIONS P2): the pause a dialog holds while it is open. "The clock (and
 * patience) keeps running behind Help, Settings, Save/Load and New Game." Taken when the dialog
 * opens: it pauses a RUNNING game and leaves any other mode alone.
 *
 * <p>{@link #release()} resumes the game only if THIS pause stopped it, and only once — a game the
 * player had paused before opening the dialog stays paused. {@link #forget()} is for a dialog
 * whose outcome replaces the world (a load, a new game): the new world decides for itself whether
 * it runs. A save hands its pause to the save, which releases it when the file is written (the
 * save's quiesce IS that pause, audit C-08).
 *
 * <p>EDT only, like the window that holds it.
 */
final class DialogPause {

    private final GameLifecycle lifecycle;
    private boolean held;

    DialogPause(GameLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.held = lifecycle.pauseIfRunning();
    }

    /** Puts the game back as it was when the dialog opened. */
    void release() {
        if (held) {
            held = false;
            lifecycle.resumeIfPaused();
        }
    }

    /** The world this pause stopped is being replaced: nothing to put back. */
    void forget() {
        held = false;
    }

    /** Whether this pause stopped the game and has not let it go yet. */
    boolean held() {
        return held;
    }
}
