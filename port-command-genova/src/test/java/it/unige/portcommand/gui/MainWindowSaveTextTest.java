package it.unige.portcommand.gui;

import it.unige.portcommand.lifecycle.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S18b: the window's own words about saving and loading, through the static helpers the window
 * uses (a {@code JFrame} cannot be built headless).
 */
class MainWindowSaveTextTest {

    /** UI tree U19: "Load confirmation in the empty shell says 'The current game will be replaced.'" */
    @Test
    void theLoadConfirmationNamesAGameOnlyWhenThereIsOne() {
        assertEquals("Load savegame.json? The current game will be replaced.",
                MainWindow.loadConfirmation("savegame.json", GameMode.RUNNING));
        assertEquals("Load savegame.json? The current game will be replaced.",
                MainWindow.loadConfirmation("savegame.json", GameMode.PAUSED));
        assertEquals("Load autosave.json?", MainWindow.loadConfirmation("autosave.json", GameMode.MENU),
                "the empty shell has no game to replace");
        assertEquals("Load autosave.json?", MainWindow.loadConfirmation("autosave.json", GameMode.GAME_OVER),
                "nor has a finished one");
    }

    /** P5: "Autosave is silent." */
    @Test
    void theAutosaveSaysWhichDayItSaved() {
        assertEquals("Autosaved (end of day 2)", MainWindow.autosavedText(2));
    }

    /** P1: "Window ✕ / Game → Exit quit with no confirm and no save." */
    @Test
    void closingTheWindowAsksFirstOnlyWhileAGameIsOn() {
        assertTrue(MainWindow.exitAsksFirst(GameMode.RUNNING, false));
        assertTrue(MainWindow.exitAsksFirst(GameMode.PAUSED, false));
        assertFalse(MainWindow.exitAsksFirst(GameMode.MENU, false), "the empty shell has nothing to lose");
        assertFalse(MainWindow.exitAsksFirst(GameMode.GAME_OVER, false), "nor has a finished game");
        assertFalse(MainWindow.exitAsksFirst(GameMode.RUNNING, true), "a save or load in flight closes as before");
    }

    /** P11: "New Game during a game: no warning." */
    @Test
    void newGameAsksFirstOverAGameThatIsOn() {
        assertTrue(MainWindow.newGameAsksFirst(GameMode.RUNNING, true));
        assertTrue(MainWindow.newGameAsksFirst(GameMode.PAUSED, true));
        assertFalse(MainWindow.newGameAsksFirst(GameMode.MENU, true));
        assertFalse(MainWindow.newGameAsksFirst(GameMode.GAME_OVER, true));
        assertFalse(MainWindow.newGameAsksFirst(GameMode.PAUSED, false),
                "from the game-over report or a finished scenario's Close: the game is over already");
    }

    /** S18b (P13): a game launched with -NoRasa says so, and says what still works. */
    @Test
    void aGameWithoutRasaSaysSoHonestly() {
        assertTrue(MainWindow.RASA_OFF_TEXT.startsWith("Rasa is off"), MainWindow.RASA_OFF_TEXT);
        assertTrue(MainWindow.RASA_OFF_TEXT.contains("Help"), "it points at the moves that still work");
        assertEquals("portcommand.rasa.off", MainWindow.RASA_OFF_PROPERTY, "the property Main sets for --no-rasa");
    }

    /** P1, P8, P11, §10: the decided wording. */
    @Test
    void theQuestionsAreTheDecidedOnes() {
        assertEquals("Quit? Unsaved progress since the last autosave will be lost.", MainWindow.QUIT_TEXT);
        assertEquals("Open negotiations will be lost. Save anyway?", MainWindow.OPEN_NEGOTIATIONS_TEXT);
        assertEquals("Save & quit", MainWindow.SAVE_AND_QUIT);
    }
}
