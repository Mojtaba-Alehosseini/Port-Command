package it.unige.portcommand.gui.model;

/**
 * Pure rendering model for a walk-in tab's live counter (S14, ADR-18): the real time the vessel
 * will still wait, as {@code m:ss}, and how urgently to colour it. No Swing/AWT import — a view
 * only sets the text and maps {@link Level} to a colour, so a UI redesign reuses this unchanged.
 *
 * <p>The counter shows a number and a colour and nothing else: it is never labelled, and nothing
 * explains why two vessels start from different values. Players discover that.
 */
public final class PatienceCounterModel {

    /** How the number is coloured. */
    public enum Level { NORMAL, WARNING, CRITICAL }

    /** Under this many seconds on the display the counter turns orange ({@link Level#WARNING}). */
    public static final int WARNING_BELOW_SECONDS = 30;
    /** Under this many seconds on the display the counter turns red ({@link Level#CRITICAL}). */
    public static final int CRITICAL_BELOW_SECONDS = 10;

    private PatienceCounterModel() {
    }

    /** {@code m:ss}. Rounds UP to the second, so it reads 4:00 at the start and 0:00 only at zero. */
    public static String text(long remainingMillis) {
        long seconds = displaySeconds(remainingMillis);
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }

    /** Judged on the DISPLAYED seconds, so the colour changes on the same tick the number does. */
    public static Level level(long remainingMillis) {
        long seconds = displaySeconds(remainingMillis);
        if (seconds < CRITICAL_BELOW_SECONDS) {
            return Level.CRITICAL;
        }
        return seconds < WARNING_BELOW_SECONDS ? Level.WARNING : Level.NORMAL;
    }

    private static long displaySeconds(long remainingMillis) {
        return remainingMillis <= 0 ? 0 : (remainingMillis + 999) / 1000;
    }
}
