package it.unige.portcommand.gui;

import it.unige.portcommand.lifecycle.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (review finding): the Settings dialog is closed in the empty shell. Since S15 a plain
 * {@code --gui} launch opens the window in MENU — no game — and everything the dialog sets belongs
 * to a game: the next start would silently discard the four knobs, and a policy typed there would
 * be confirmed "active now" with no Assistant agent alive to register it. The window itself is a
 * {@code JFrame} and cannot be built headless, so the rule lives in a pure function that both the
 * menu enablement ({@code reflectMode}) and the apply path ({@code applySettings}) ask.
 */
class MainWindowSettingsGateTest {

    @Test
    void settingsAreClosedOnlyWhileNoGameExists() {
        assertFalse(MainWindow.settingsAvailableIn(GameMode.MENU), "the empty shell: nothing to configure");
        for (GameMode mode : GameMode.values()) {
            assertEquals(mode != GameMode.MENU, MainWindow.settingsAvailableIn(mode),
                    "every other mode is as it was before S15: " + mode);
        }
    }

    /** S18b (adversarial review): a policy set while a world was being replaced reached an
     * Assistant that was leaving or not yet there - lost behind "Policy set." (PT19's symptom). */
    @Test
    void settingsWaitForALoadToFinish() {
        assertFalse(MainWindow.menuState(GameMode.RUNNING, true, false).settings(), "a load or a new game has begun");
        assertFalse(MainWindow.menuState(GameMode.LOADING, false, false).settings(), "the world is being replaced");
        assertTrue(MainWindow.menuState(GameMode.PAUSED, false, false).settings());
        assertTrue(MainWindow.menuState(GameMode.PAUSED, false, true).settings(), "a save does not close Settings");
        assertFalse(MainWindow.menuState(GameMode.MENU, false, false).settings(), "S15: not in the empty shell");
    }

    /** S18b (adversarial review, audit C-08): a manual save's pause is its quiesce - nothing on the
     * Game menu may end it, or replace the world under it, until the file is written. */
    @Test
    void aSaveInFlightHoldsTheGameMenu() {
        MainWindow.MenuState saving = MainWindow.menuState(GameMode.PAUSED, false, true);
        assertFalse(saving.resume(), "Resume used to restart the clock under the save's snapshot");
        assertFalse(saving.load(), "nor may a load replace the world in the middle of a save");
        assertFalse(saving.newGame());
        assertFalse(MainWindow.menuState(GameMode.RUNNING, false, true).pause());

        MainWindow.MenuState written = MainWindow.menuState(GameMode.PAUSED, false, false);
        assertTrue(written.resume() && written.load() && written.newGame(), "all of it is back once it is written");
        assertTrue(MainWindow.menuState(GameMode.RUNNING, false, false).pause());
        assertFalse(MainWindow.menuState(GameMode.PAUSED, true, false).load(), "a load in flight: no second one");
    }
}
