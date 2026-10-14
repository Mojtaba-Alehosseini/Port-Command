package it.unige.portcommand.gui;

import java.util.Optional;

import it.unige.portcommand.scenario.ScenarioRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S18b (playtest 2, PT2): the Tutorial starts paused, so the window puts its first card on screen
 * itself - read here from the real scenario file, through the same static helper the window uses
 * (the window is a {@code JFrame} and cannot be built headless).
 */
class MainWindowTutorialStartTest {

    @Test
    void theTutorialsFirstCardIsReadFromItsScript() {
        ScenarioRegistry registry = new ScenarioRegistry();

        Optional<String> first = MainWindow.firstScriptedTutorialStep(registry.findByKey("tutorial").orElseThrow());

        assertTrue(first.orElseThrow().startsWith("Welcome, Harbour Master!"), first.orElse("(none)"));
    }

    @Test
    void aScenarioWithoutATutorialHasNoFirstCard() {
        ScenarioRegistry registry = new ScenarioRegistry();

        assertEquals(Optional.empty(), MainWindow.firstScriptedTutorialStep(registry.findByKey("busy_day").orElseThrow()));
        assertEquals(Optional.empty(), MainWindow.firstScriptedTutorialStep(registry.findByKey("storm").orElseThrow()));
    }
}
