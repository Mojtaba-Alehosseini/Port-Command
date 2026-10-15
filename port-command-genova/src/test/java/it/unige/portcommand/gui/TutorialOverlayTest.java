package it.unige.portcommand.gui;

import java.awt.Container;
import java.util.List;
import java.util.Locale;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.scenario.events.TutorialStepAdvancedEvent;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S19 (playtest 5, PT23): the tutorial is a short TOUR at the paused start. Playtest 5 found the
 * cards hard to follow: both "Skip tutorial" and "×" on a card, "Got it" hid the card instead of
 * showing the next one, and the next card popped up later by itself, on the scenario's clock. Now:
 * no ×; "Next" shows the next card at once; the last card's "Start playing" resumes the paused
 * game; "Skip tutorial" ends the tour; and no game event drives a card.
 *
 * <p>Kept from before: a world swap clears the overlay at once (audit C-13), the top bar's card sits
 * under the bar (PT18), and the canonical texts point at components the screen really names and
 * never explain the walk-in's time counter (S15).
 *
 * <p>Headless throughout: {@code JComponent} construction is not gated by headless mode, bus
 * delivery is {@code DeliveryMode.EDT}, and every assertion is taken after an
 * {@code invokeAndWait} flush.
 */
class TutorialOverlayTest {

    private static final List<String> TOUR = List.of("card 1", "card 2", "card 3", "card 4", "card 5");

    private static TutorialOverlay overlay(EventBus bus) {
        Container root = new JPanel();
        return new TutorialOverlay(bus, root);
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    @Timeout(20)
    void nextShowsTheNextCardAtOnce() throws Exception {
        TutorialOverlay overlay = overlay(new EventBus());
        SwingUtilities.invokeAndWait(() -> overlay.startTour(TOUR, null));
        for (int step = 1; step <= TutorialOverlay.TOTAL_STEPS; step++) {
            assertTrue(overlay.isShowingAStep(), "card " + step + " is up");
            assertTrue(overlay.captionForTest().contains("card " + step), overlay.captionForTest());
            assertEquals(step < TutorialOverlay.TOTAL_STEPS ? "Next" : "Done", overlay.nextLabelForTest());
            if (step < TutorialOverlay.TOTAL_STEPS) {
                SwingUtilities.invokeAndWait(overlay::advanceForTest);
            }
        }
    }

    @Test
    @Timeout(20)
    void theLastCardStartsTheGame() throws Exception {
        TutorialOverlay overlay = overlay(new EventBus());
        int[] started = new int[1];
        int[] ended = new int[1];
        overlay.onClosedForGood(() -> ended[0]++);
        SwingUtilities.invokeAndWait(() -> overlay.startTour(TOUR, () -> started[0]++));
        for (int step = 1; step < TutorialOverlay.TOTAL_STEPS; step++) {
            SwingUtilities.invokeAndWait(overlay::advanceForTest);
        }
        assertEquals("Start playing", overlay.nextLabelForTest(), "the tour at the paused start ends by starting it");
        assertEquals(0, started[0]);

        SwingUtilities.invokeAndWait(overlay::advanceForTest);

        assertFalse(overlay.isShowingAStep());
        assertEquals(1, started[0], "the game goes on");
        assertEquals(1, ended[0], "and the window remembers the tutorial was seen");
    }

    /** S19 review (PT23): resumed with ▶ part-way through the tour, the last card has nothing to start. */
    @Test
    @Timeout(20)
    void aGameResumedDuringTheTourEndsItOnDone() throws Exception {
        TutorialOverlay overlay = overlay(new EventBus());
        int[] started = new int[1];
        SwingUtilities.invokeAndWait(() -> overlay.startTour(TOUR, () -> started[0]++));
        for (int step = 1; step < TutorialOverlay.TOTAL_STEPS; step++) {
            SwingUtilities.invokeAndWait(overlay::advanceForTest);
        }
        assertEquals("Start playing", overlay.nextLabelForTest(), "fixture");

        SwingUtilities.invokeAndWait(overlay::gameRunning);

        assertEquals("Done", overlay.nextLabelForTest(), "the game runs: there is nothing to start");
        SwingUtilities.invokeAndWait(overlay::advanceForTest);
        assertEquals(0, started[0]);
        assertFalse(overlay.isShowingAStep());
    }

    @Test
    @Timeout(20)
    void skipEndsTheTourAndStartsNothing() throws Exception {
        TutorialOverlay overlay = overlay(new EventBus());
        int[] started = new int[1];
        int[] ended = new int[1];
        overlay.onClosedForGood(() -> ended[0]++);
        SwingUtilities.invokeAndWait(() -> overlay.startTour(TOUR, () -> started[0]++));
        SwingUtilities.invokeAndWait(overlay::advanceForTest);

        SwingUtilities.invokeAndWait(overlay::skipForTest);

        assertFalse(overlay.isShowingAStep());
        assertEquals(0, started[0], "Skip only ends the tour; the player presses Resume");
        assertEquals(1, ended[0]);
    }

    @Test
    void theCardHasNoCloseButton() {
        TutorialOverlay overlay = overlay(new EventBus());
        overlay.startTour(TOUR, null);
        assertEquals(List.of("Skip tutorial", "Next"), overlay.buttonLabelsForTest(),
                "one way out in words, one way on - no ×");
    }

    @Test
    @Timeout(20)
    void noGameEventShowsACard() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);
        for (int step = 1; step <= TutorialOverlay.TOTAL_STEPS; step++) {
            bus.publish(new TutorialStepAdvancedEvent(step, "scripted step " + step));
        }
        flush();
        assertFalse(overlay.isShowingAStep(), "the scenario's clock does not drive the cards any more");

        SwingUtilities.invokeAndWait(() -> overlay.startTour(TOUR, null));
        bus.publish(new TutorialStepAdvancedEvent(4, "scripted step 4"));
        flush();
        assertTrue(overlay.captionForTest().contains("card 1"), "nor does it change the card being read");
    }

    @Test
    @Timeout(20)
    void helpShowTutorialPagesTheCanonicalCardsAndEndsOnDone() throws Exception {
        TutorialOverlay overlay = overlay(new EventBus());
        int[] ended = new int[1];
        overlay.onClosedForGood(() -> ended[0]++);
        SwingUtilities.invokeAndWait(overlay::startFromBeginning);
        assertTrue(overlay.captionForTest().contains("Port table"), overlay.captionForTest());
        for (int step = 1; step < TutorialOverlay.TOTAL_STEPS; step++) {
            SwingUtilities.invokeAndWait(overlay::advanceForTest);
        }
        assertEquals("Done", overlay.nextLabelForTest(), "a tour the player asked for mid-game starts nothing");

        SwingUtilities.invokeAndWait(overlay::advanceForTest);

        assertFalse(overlay.isShowingAStep());
        assertEquals(1, ended[0]);
    }

    @Test
    @Timeout(20)
    void aMissingCardTextIsTheCanonicalOne() throws Exception {
        TutorialOverlay overlay = overlay(new EventBus());
        SwingUtilities.invokeAndWait(() -> overlay.startTour(List.of("the scenario's first card"), null));
        assertTrue(overlay.captionForTest().contains("the scenario's first card"));
        SwingUtilities.invokeAndWait(overlay::advanceForTest);
        assertTrue(overlay.captionForTest().contains("chat tab"), "card 2 falls back: " + overlay.captionForTest());
    }

    @Test
    @Timeout(20)
    void aWorldSwapClearsTheOverlayImmediately() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);
        SwingUtilities.invokeAndWait(() -> overlay.startTour(TOUR, null));
        assertTrue(overlay.isShowingAStep());

        // DEMO_SCRIPT Beat 5 switches Tutorial -> Storm mid-run; the tutorial's scrim must not
        // survive into a world it does not describe (audit C-13).
        bus.publish(new GameLoadedEvent(1, 15_000.0, 50));
        flush();
        assertFalse(overlay.isShowingAStep(), "a load/scenario switch must dismiss the tutorial scrim");
    }

    /** PT18: "the step-5 box (about the top bar) sits at the bottom". It goes under the bar. */
    @Test
    void theTopBarsCardSitsUnderTheBar() {
        java.awt.Rectangle topBar = new java.awt.Rectangle(0, 0, 1200, 48);
        assertEquals(56, TutorialOverlay.cardY(topBar, true, 800, 120));
        assertEquals(800 - 120 - 24, TutorialOverlay.cardY(topBar, false, 800, 120),
                "any other highlight in the top half still puts the card in the bottom half");
        assertEquals(24, TutorialOverlay.cardY(new java.awt.Rectangle(0, 600, 1200, 100), false, 800, 120));
        assertEquals(24, TutorialOverlay.cardY(null, true, 800, 120), "no highlight found: the top, as before");
    }

    @Test
    void theFirstStepDescribesAndHighlightsThePortTable() {
        assertEquals("This is the Port table — each berth shows the vessel holding it and until when; "
                + "the lines below count the queue and the tugs.", TutorialOverlay.canonicalStepTextForTest(1));
        assertEquals(GameScreen.NAME_PORT, TutorialOverlay.stepTargetNameForTest(1));
        assertEquals("tutorial-port", GameScreen.NAME_PORT, "the name the Port table carries on screen");
    }

    @Test
    void everyStepHighlightsAComponentTheScreenReallyNames() {
        // The overlay finds its target by component NAME; a step whose name no component carries
        // gets no cutout and the scrim silently dims everything.
        List<String> named = List.of(GameScreen.NAME_HUD, GameScreen.NAME_PORT, GameScreen.NAME_CHAT,
                GameScreen.NAME_COMMLOG);
        for (int step = 1; step <= TutorialOverlay.TOTAL_STEPS; step++) {
            assertTrue(named.contains(TutorialOverlay.stepTargetNameForTest(step)),
                    "step " + step + " targets '" + TutorialOverlay.stepTargetNameForTest(step) + "'");
        }
        assertEquals(List.of(GameScreen.NAME_PORT, GameScreen.NAME_CHAT, GameScreen.NAME_CHAT,
                        GameScreen.NAME_COMMLOG, GameScreen.NAME_HUD),
                List.of(TutorialOverlay.stepTargetNameForTest(1), TutorialOverlay.stepTargetNameForTest(2),
                        TutorialOverlay.stepTargetNameForTest(3), TutorialOverlay.stepTargetNameForTest(4),
                        TutorialOverlay.stepTargetNameForTest(5)),
                "left column, chat, chat (the Hint button), comm log, top bar");
    }

    @Test
    void noStepTextExplainsTheWalkInsTimeCounter() {
        // INVARIANTS: the counter is a bare m:ss number plus a colour, never labelled or explained —
        // not in a caption, not in a tooltip, and not in the tutorial.
        for (int step = 1; step <= TutorialOverlay.TOTAL_STEPS; step++) {
            String text = TutorialOverlay.canonicalStepTextForTest(step).toLowerCase(Locale.ROOT);
            // ("counter" alone would trip over step 3's "counter-offer".)
            for (String forbidden : List.of("patience", "time counter", "countdown", "timer", "time left",
                    "remaining", "walk away", "seconds", "m:ss")) {
                assertFalse(text.contains(forbidden), "step " + step + " mentions '" + forbidden + "': " + text);
            }
        }
    }
}
