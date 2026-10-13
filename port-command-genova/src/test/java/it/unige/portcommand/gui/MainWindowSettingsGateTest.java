package it.unige.portcommand.gui;

import it.unige.portcommand.lifecycle.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
