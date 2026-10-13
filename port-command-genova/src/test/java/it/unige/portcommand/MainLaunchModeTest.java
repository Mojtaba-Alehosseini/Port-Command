package it.unige.portcommand;

import it.unige.portcommand.Main.LaunchMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): what a launch boots, from its flags alone.
 *
 * <p>Until S15 every launch without {@code --scenario} or {@code --load} started a sandbox, window
 * or not, and the window opened on a game the player had not chosen. Now a plain {@code --gui}
 * launch boots only the empty shell and the window asks; everything else is as it was. That
 * four-way decision is the pure {@code Main.launchMode}, pinned here — {@code Main.main} itself
 * boots JADE and blocks forever, so the branch is otherwise reachable only by running the program.
 */
class MainLaunchModeTest {

    @Test
    void withNoFlagsAHeadlessSandboxStartsAsItAlwaysDid() {
        // Nobody to show a dialog to: the headless default must still be a running game.
        assertEquals(LaunchMode.HEADLESS_SANDBOX, Main.launchMode(false, false, false));
    }

    @Test
    void guiAloneOpensTheWindowFirstAndLetsThePlayerChoose() {
        assertEquals(LaunchMode.WINDOW_THEN_NEW_GAME, Main.launchMode(true, false, false));
    }

    @Test
    void aScenarioOnTheCommandLineWinsWithOrWithoutAWindow() {
        // The demo launchers pass --gui --scenario <key> and rely on the world booting first.
        assertEquals(LaunchMode.SCENARIO, Main.launchMode(true, false, true));
        assertEquals(LaunchMode.SCENARIO, Main.launchMode(false, false, true));
    }

    @Test
    void aSaveOnTheCommandLineWinsWithOrWithoutAWindow() {
        assertEquals(LaunchMode.LOAD, Main.launchMode(true, true, false));
        assertEquals(LaunchMode.LOAD, Main.launchMode(false, true, false));
    }

    @Test
    void loadTogetherWithScenarioIsRefusedBeforeAnythingBoots() {
        for (boolean gui : new boolean[] {false, true}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> Main.launchMode(gui, true, true));
            assertTrue(refused.getMessage().contains("--load") && refused.getMessage().contains("--scenario"),
                    "the message must name both flags: " + refused.getMessage());
        }
    }

    @Test
    void onlyTheWindowFirstModeLeavesTheGameToTheNewGameDialog() {
        // Main passes offerNewGameOnOpen = (mode == WINDOW_THEN_NEW_GAME) to the window. Every
        // other combination that has a window has already booted a world, so the window must not
        // offer a second one on top of it; walked over the whole flag space so a fifth mode added
        // later cannot slip past.
        for (boolean gui : new boolean[] {false, true}) {
            for (boolean hasLoad : new boolean[] {false, true}) {
                for (boolean hasScenario : new boolean[] {false, true}) {
                    if (hasLoad && hasScenario) {
                        continue; // refused, see above
                    }
                    LaunchMode mode = Main.launchMode(gui, hasLoad, hasScenario);
                    boolean windowWithNoWorld = gui && !hasLoad && !hasScenario;
                    assertEquals(windowWithNoWorld, mode == LaunchMode.WINDOW_THEN_NEW_GAME,
                            "gui=" + gui + " load=" + hasLoad + " scenario=" + hasScenario + " -> " + mode);
                }
            }
        }
    }
}
