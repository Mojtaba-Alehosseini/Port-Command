package it.unige.portcommand.assistant;

/**
 * How long the autopilot lets a move be seen coming (S17a, ADR-21, playtest item PT12).
 *
 * <p>The autopilot used to answer a vessel in the same millisecond its offer arrived, so a whole
 * negotiation was over in a blink and there was nothing to watch - or to interrupt. It now waits
 * {@link #moveDelayMillis} of REAL time between deciding a move and sending it, with the tab
 * marked "Assistant is negotiating" meanwhile; "Take over" during that wait cancels the move.
 *
 * <p>The value is injected, never read from a global: a launched game is given
 * {@link #GAME_MOVE_DELAY_MILLIS} through {@code BootstrapConfig}, and everything a test builds
 * gets {@link #NONE} unless it asks for something else - with no delay the move is sent in the
 * same step, so no test waits and none depends on a timer.
 *
 * @param moveDelayMillis real milliseconds between a decision and its dispatch; 0 = at once
 */
public record AutopilotPacing(long moveDelayMillis) {

    /** About two seconds per move: long enough to read the vessel's offer and reach "Take over". */
    public static final long GAME_MOVE_DELAY_MILLIS = 2_000L;

    /** No pacing: a move is sent in the step that decided it. */
    public static final AutopilotPacing NONE = new AutopilotPacing(0L);

    public AutopilotPacing {
        if (moveDelayMillis < 0) {
            throw new IllegalArgumentException("moveDelayMillis must be >= 0: " + moveDelayMillis);
        }
    }

    public boolean paced() {
        return moveDelayMillis > 0;
    }
}
