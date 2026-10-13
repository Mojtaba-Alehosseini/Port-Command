package it.unige.portcommand.gui.model;

/**
 * What the player picked in the New Game dialog (S15): one of the scripted scenarios, or free
 * play. Free play is a dialog-level choice, never a fourth scenario key — the scenario registry
 * stays locked at its three.
 *
 * @param scenarioKey the scenario's key, or {@code null} for free play
 */
public record NewGameChoice(String scenarioKey) {

    public static NewGameChoice freePlay() {
        return new NewGameChoice(null);
    }

    public static NewGameChoice scenario(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a scenario choice needs a key");
        }
        return new NewGameChoice(key);
    }

    public boolean isFreePlay() {
        return scenarioKey == null;
    }
}
