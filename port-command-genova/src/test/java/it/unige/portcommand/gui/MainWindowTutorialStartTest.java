package it.unige.portcommand.gui;

import java.util.Optional;

import it.unige.portcommand.scenario.Scenario;
import it.unige.portcommand.scenario.ScenarioRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S18b (playtest 2, PT2): the Tutorial starts paused, so the window puts its first card on screen
 * itself - read here from the real scenario file, through the same static helper the window uses
 * (the window is a {@code JFrame} and cannot be built headless).
 */
class MainWindowTutorialStartTest {

    /** S19 (playtest 5, PT23): the tour shows the tutorial's five cards at its paused start. */
    @Test
    void theTutorialsTourIsItsFiveScriptedCardsInOrder() {
        ScenarioRegistry registry = new ScenarioRegistry();

        java.util.List<String> cards = MainWindow.scriptedTutorialSteps(registry.findByKey("tutorial").orElseThrow());

        assertEquals(5, cards.size(), cards.toString());
        assertTrue(cards.get(0).startsWith("Welcome, Harbour Master!"), cards.get(0));
        assertTrue(cards.stream().noneMatch(String::isBlank), cards.toString());
    }

    /** S19 review (PT23): the first-launch tour is at a PAUSED start too - a free-play game starts
     * running, so the window pauses it first; never while a save or a load owns the pause. */
    @Test
    void theFirstLaunchTourPausesARunningGameFirst() {
        assertTrue(MainWindow.pauseForTheTour(it.unige.portcommand.lifecycle.GameMode.RUNNING, false));
        assertFalse(MainWindow.pauseForTheTour(it.unige.portcommand.lifecycle.GameMode.PAUSED, false),
                "already paused");
        assertFalse(MainWindow.pauseForTheTour(it.unige.portcommand.lifecycle.GameMode.RUNNING, true),
                "a save or a load owns the pause");
        assertFalse(MainWindow.pauseForTheTour(it.unige.portcommand.lifecycle.GameMode.MENU, false), "no game");
    }

    /** S18b (adversarial review): {@code --load} of a tutorial save past its first step showed
     * card 1 again. The window shows that card only at the tutorial's opening minute. */
    @Test
    void theWindowShowsTheFirstCardOnlyAtTheTutorialsOpening() {
        ScenarioRegistry registry = new ScenarioRegistry();
        Scenario tutorial = registry.findByKey("tutorial").orElseThrow();
        long opening = tutorial.initialState().startSimMillis();

        assertTrue(MainWindow.firstCardStillToCome(tutorial, opening), "a fresh start waits, paused, at its opening");
        assertTrue(MainWindow.firstCardStillToCome(tutorial, opening + 3_000L),
                "a started world runs for an instant before it is paused - a tick may move the clock");
        assertFalse(MainWindow.firstCardStillToCome(tutorial, opening + 5 * 60_000L),
                "five minutes in, its script has shown the card");
        assertFalse(MainWindow.firstCardStillToCome(registry.findByKey("busy_day").orElseThrow(), opening),
                "a scenario with no tutorial has no first card");
    }

    @Test
    void aScenarioWithoutATutorialHasNoFirstCard() {
        ScenarioRegistry registry = new ScenarioRegistry();

        assertEquals(java.util.List.of(), MainWindow.scriptedTutorialSteps(registry.findByKey("busy_day").orElseThrow()));
        assertEquals(java.util.List.of(), MainWindow.scriptedTutorialSteps(registry.findByKey("storm").orElseThrow()));
    }
}
