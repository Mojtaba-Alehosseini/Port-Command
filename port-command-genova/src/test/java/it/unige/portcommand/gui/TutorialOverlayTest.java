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
 * Audit C-03 / C-13 (2026-07-27) — the scripted tutorial had no ending.
 *
 * <p>{@code tutorial.json} fires exactly five {@code TutorialStepAdvance} events, the last at
 * simTimeSeconds 1500, and there is no sixth. The ONLY route to {@code dismiss()} was
 * {@code advance()}, which fires on the Next/Done <b>button</b> — the MANUAL driver's ending. So a
 * scenario-driven tutorial parked itself on step 5 permanently. At the tutorial's 1800
 * real-seconds/day pacing all five steps land within the first ~31 real seconds, and the 60 %
 * black scrim then covered every panel under it for the whole remaining session, including
 * the storm beat and the EOD report. Clicks still passed through, so it was purely — and
 * severely — visual, which is exactly the kind of thing a headless suite cannot see and a demo
 * audience cannot miss.
 *
 * <p>S15 (2026-10-12): the first step now points at the Port table. The last three cases pin the
 * canonical texts and the highlight targets — that step 1 names the Port table and its
 * {@code GameScreen} component, that every step's target is a name {@code GameScreen} really
 * assigns ({@code GameScreenCompositionTest} checks the components exist), and that no step text
 * explains the walk-in's time counter.
 *
 * <p>Headless throughout: {@code JComponent} construction is not gated by headless mode (only
 * top-level Window realization is), bus delivery is {@code DeliveryMode.EDT}, and every assertion
 * is taken after an {@code invokeAndWait} flush — the idiom {@code ChatPanelTest} and
 * {@code GuiPanelsSubscriptionLifecycleTest} established. Waits are bounded polls with an explicit
 * deadline (design rule: no untimed waits).
 */
class TutorialOverlayTest {

    /** Well under the production 12 s dwell, so the test does not sit on a real timer. */
    private static final int FAST_DISMISS_MILLIS = 60;
    private static final long DEADLINE_MILLIS = 5_000;

    private static TutorialOverlay overlay(EventBus bus) {
        Container root = new JPanel();
        return new TutorialOverlay(bus, root, FAST_DISMISS_MILLIS);
    }

    @Test
    @Timeout(20)
    void theFinalScriptedStepDismissesItselfSoTheScrimCannotOutliveTheTutorial() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);

        for (int step = 1; step <= TutorialOverlay.TOTAL_STEPS; step++) {
            bus.publish(new TutorialStepAdvancedEvent(step, "scripted step " + step));
        }
        SwingUtilities.invokeAndWait(() -> { });
        // S18b (PT18): steps that arrive while a card is read wait for its "Got it".
        for (int step = 1; step < TutorialOverlay.TOTAL_STEPS; step++) {
            SwingUtilities.invokeAndWait(overlay::advanceForTest);
        }
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(overlay.isShowingAStep(), "the final scripted step must be shown before it clears");
        assertTrue(overlay.captionForTest().contains("scripted step 5"), overlay.captionForTest());

        assertTrue(awaitDismissed(overlay),
                "the scrim must clear itself after the last scripted step — there is no sixth event "
                        + "and no Done click on the scripted path");
    }

    @Test
    @Timeout(20)
    void anIntermediateScriptedStepDoesNotArmTheDismiss() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);

        bus.publish(new TutorialStepAdvancedEvent(2, "scripted step 2"));
        SwingUtilities.invokeAndWait(() -> { });

        // Give the (unarmed) timer several dwells' worth of chances to fire.
        for (int i = 0; i < 10; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(FAST_DISMISS_MILLIS / 2);
        }
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(overlay.isShowingAStep(), "steps 1-4 must stay up until the script advances them");
    }

    @Test
    @Timeout(20)
    void manualPagingStillWaitsForTheDoneButton() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);

        // Help -> "Show tutorial" pages with the CANONICAL text (scenarioText == null), which must
        // never auto-dismiss: the player is reading at their own speed.
        SwingUtilities.invokeAndWait(overlay::startFromBeginning);
        for (int i = 0; i < 10; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(FAST_DISMISS_MILLIS / 2);
        }
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(overlay.isShowingAStep(), "manual paging must not be torn down by the scripted dwell");
    }

    /**
     * The interaction actually worth pinning, and the one the first cut of this suite missed
     * (adversarial review, 2026-07-27): the MANUAL driver paging all the way to step 5. That is the
     * exact shape the auto-dismiss must NOT fire on — {@code step == TOTAL_STEPS} is true, and only
     * {@code scripted} distinguishes it. {@code manualPagingStillWaitsForTheDoneButton} below only
     * ever reached step 1, so it could not have caught a fix that keyed on the step alone.
     */
    @Test
    @Timeout(20)
    void manualPagingToTheFINALStepStillWaitsForTheDoneButton() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);

        SwingUtilities.invokeAndWait(overlay::startFromBeginning);
        for (int step = 1; step < TutorialOverlay.TOTAL_STEPS; step++) {
            SwingUtilities.invokeAndWait(overlay::advanceForTest);
        }
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(overlay.isShowingAStep(), "setup: manual paging must have reached the final step");

        for (int i = 0; i < 10; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(FAST_DISMISS_MILLIS / 2);
        }
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(overlay.isShowingAStep(),
                "the player is reading at their own speed on the manual path — only Done ends it");

        SwingUtilities.invokeAndWait(overlay::advanceForTest); // Done
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(overlay.isShowingAStep(), "Done on the final step dismisses");
    }

    @Test
    @Timeout(20)
    void aWorldSwapClearsTheOverlayImmediately() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);

        bus.publish(new TutorialStepAdvancedEvent(3, "scripted step 3"));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(overlay.isShowingAStep());

        // DEMO_SCRIPT Beat 5 switches Tutorial -> Storm mid-run; the tutorial's scrim must not
        // survive into a world it does not describe (audit C-13).
        bus.publish(new GameLoadedEvent(1, 15_000.0, 50));
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(overlay.isShowingAStep(), "a load/scenario switch must dismiss the tutorial scrim");
    }

    // ==================== S15: what the five canonical steps say and point at ====================

    // ---- S18b (playtests 3-4: PT1, PT18, P10) ----

    private static void publishAndFlush(EventBus bus, int step) throws Exception {
        bus.publish(new TutorialStepAdvancedEvent(step, "scripted step " + step));
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** PT18: "come back after Close (step 4/5)". Closing is for the rest of this game. */
    @Test
    @Timeout(20)
    void aClosedTutorialShowsNoMoreStepsInThisGame() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);
        publishAndFlush(bus, 1);
        SwingUtilities.invokeAndWait(overlay::closeForTest);
        assertFalse(overlay.isShowingAStep());

        publishAndFlush(bus, 4);
        publishAndFlush(bus, 5);
        assertFalse(overlay.isShowingAStep(), "after Close no later step may show");
    }

    @Test
    @Timeout(20)
    void skipEndsTheTutorialLikeClose() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);
        publishAndFlush(bus, 2);
        SwingUtilities.invokeAndWait(overlay::skipForTest);

        publishAndFlush(bus, 3);
        assertFalse(overlay.isShowingAStep());
    }

    /** PT1: "step 5 card shows again after clicking Done". Done ends it, whichever driver. */
    @Test
    @Timeout(20)
    void doneOnTheLastStepEndsTheTutorialForThisGame() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);
        SwingUtilities.invokeAndWait(overlay::startFromBeginning);
        for (int step = 1; step <= TutorialOverlay.TOTAL_STEPS; step++) {
            SwingUtilities.invokeAndWait(overlay::advanceForTest); // Next x4, then Done
        }
        assertFalse(overlay.isShowingAStep());

        publishAndFlush(bus, 5);
        assertFalse(overlay.isShowingAStep(), "the script's step 5 must not bring the card back");
    }

    /** PT18: "move on without Next". A step that arrives while a card is read waits for it. */
    @Test
    @Timeout(20)
    void aScriptedStepNeverReplacesTheCardBeingRead() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);
        publishAndFlush(bus, 1);
        assertEquals("Got it", overlay.nextLabelForTest(), "a scripted card is read, not paged");

        publishAndFlush(bus, 2);
        assertTrue(overlay.captionForTest().contains("scripted step 1"), overlay.captionForTest());

        SwingUtilities.invokeAndWait(overlay::advanceForTest);
        assertTrue(overlay.captionForTest().contains("scripted step 2"), "Got it shows the step that waited");
        SwingUtilities.invokeAndWait(overlay::advanceForTest);
        assertFalse(overlay.isShowingAStep(), "nothing waits: the card is put away until the next step");

        publishAndFlush(bus, 3);
        assertTrue(overlay.captionForTest().contains("scripted step 3"));
    }

    /** PT1: each step shows once - a step already shown, or an older one, is not shown again. */
    @Test
    @Timeout(20)
    void eachStepShowsOnce() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);
        publishAndFlush(bus, 3);
        SwingUtilities.invokeAndWait(overlay::advanceForTest);

        publishAndFlush(bus, 3);
        publishAndFlush(bus, 1);
        assertFalse(overlay.isShowingAStep());
    }

    @Test
    @Timeout(20)
    void aNewGameHasItsOwnTutorial() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);
        publishAndFlush(bus, 1);
        SwingUtilities.invokeAndWait(overlay::closeForTest);

        bus.publish(new GameLoadedEvent(1, 15_000.0, 50));
        publishAndFlush(bus, 1);
        assertTrue(overlay.isShowingAStep(), "a new world starts the tutorial afresh");
    }

    /** P10: the window remembers an ended tutorial (a preference) through this callback. */
    @Test
    @Timeout(20)
    void endingTheTutorialIsReportedOnceEachTime() throws Exception {
        EventBus bus = new EventBus();
        TutorialOverlay overlay = overlay(bus);
        int[] told = new int[1];
        overlay.onClosedForGood(() -> told[0]++);

        publishAndFlush(bus, 1);
        SwingUtilities.invokeAndWait(overlay::advanceForTest); // "Got it" is not an ending
        assertEquals(0, told[0]);
        SwingUtilities.invokeAndWait(overlay::startFromBeginning);
        SwingUtilities.invokeAndWait(overlay::closeForTest);
        assertEquals(1, told[0]);
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
        // gets no cutout and the scrim silently dims everything. Step 1 pointed at such a name the
        // moment its old target was removed, which is what this guards against happening again.
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
        // not in a caption, not in a tooltip, and not in the tutorial. The waiting list sits right
        // above the Port table the first step points at, so this is where the temptation is.
        for (int step = 1; step <= TutorialOverlay.TOTAL_STEPS; step++) {
            String text = TutorialOverlay.canonicalStepTextForTest(step).toLowerCase(Locale.ROOT);
            // ("counter" alone would trip over step 3's "counter-offer".)
            for (String forbidden : List.of("patience", "time counter", "countdown", "timer", "time left",
                    "remaining", "walk away", "seconds", "m:ss")) {
                assertFalse(text.contains(forbidden), "step " + step + " mentions '" + forbidden + "': " + text);
            }
        }
    }

    /** Bounded poll — fails by returning false at the deadline rather than hanging. */
    private static boolean awaitDismissed(TutorialOverlay overlay) throws Exception {
        long deadline = System.nanoTime() + DEADLINE_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> { });
            if (!overlay.isShowingAStep()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
