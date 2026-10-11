package it.unige.portcommand.gui.model;

/**
 * Pure model for the Settings day-length control (S14): the eight lengths a game day can take at
 * ×1 speed, from 5 real minutes to 2 real hours. No Swing/AWT import — a slider, a combo box or
 * the redesigned UI all index into the same list.
 *
 * <p>The control sets the ×1 BASE pace ({@code SimClock.realSecondsPerGameDay}); the speed control
 * multiplies it. The scenarios' own bases (15 and 30 minutes) and the sandbox default (1 hour) are
 * all on the list, so opening the dialog never has to misrepresent the running pace — but a value
 * that is NOT on it (a {@code --day-length} test pace) still reads as its nearest step.
 */
public final class DayLengthSteps {

    private static final long[] SECONDS = {300L, 600L, 900L, 1_800L, 2_700L, 3_600L, 5_400L, 7_200L};

    private DayLengthSteps() {
    }

    /** How many steps there are (slider positions {@code 0 .. count()-1}). */
    public static int count() {
        return SECONDS.length;
    }

    /** Real seconds per game day at step {@code index}. */
    public static long secondsAt(int index) {
        return SECONDS[index];
    }

    /** The step closest to {@code seconds} (the shorter one on a tie). */
    public static int nearestIndex(long seconds) {
        int best = 0;
        for (int i = 1; i < SECONDS.length; i++) {
            if (Math.abs(SECONDS[i] - seconds) < Math.abs(SECONDS[best] - seconds)) {
                best = i;
            }
        }
        return best;
    }

    /** Whether {@code seconds} is exactly one of the steps. */
    public static boolean isStep(long seconds) {
        return SECONDS[nearestIndex(seconds)] == seconds;
    }

    /** {@code 5 min}, {@code 45 min}, {@code 1 h}, {@code 1.5 h}; any other value in seconds. */
    public static String label(long seconds) {
        if (seconds % 3_600L == 0) {
            return (seconds / 3_600L) + " h";
        }
        if (seconds % 1_800L == 0 && seconds > 3_600L) {
            return (seconds / 3_600L) + ".5 h";
        }
        if (seconds % 60L == 0) {
            return (seconds / 60L) + " min";
        }
        return seconds + " s";
    }
}
