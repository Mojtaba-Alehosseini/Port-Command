package it.unige.portcommand.gui.model;

import java.util.List;

import it.unige.portcommand.core.Settings;

/**
 * Pure model for the game-speed control (S14): the choices on offer and how to label them. No
 * Swing/AWT import, so whatever widget shows the control (HUD toggles today, a menu, the
 * redesigned UI) asks the same class. The canonical set lives in {@link Settings#GAME_SPEEDS}
 * because the setting is validated and saved there.
 */
public final class GameSpeedModel {

    private GameSpeedModel() {
    }

    /** The multipliers on offer, slowest first. */
    public static List<Integer> options() {
        return Settings.GAME_SPEEDS;
    }

    /** {@code ×1}, {@code ×2}, {@code ×4}. */
    public static String label(int multiplier) {
        return "×" + multiplier;
    }

    /** A value read from anywhere (an old save, an event) as a valid choice: unknown reads as ×1. */
    public static int normalise(int multiplier) {
        return options().contains(multiplier) ? multiplier : options().get(0);
    }
}
