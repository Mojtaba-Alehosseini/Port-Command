package it.unige.portcommand.gui;

import java.awt.Container;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.scenario.events.TutorialStepAdvancedEvent;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
 * black scrim then covered the map, chat and comm log for the whole remaining session, including
 * the storm beat and the EOD report. Clicks still passed through, so it was purely — and
 * severely — visual, which is exactly the kind of thing a headless suite cannot see and a demo
 * audience cannot miss.
 *
 * <p>Headless throughout: {@code JComponent} construction is not gated by headless mode (only
 * top-level Window realization is), bus delivery is {@code DeliveryMode.EDT}, and every assertion
 * is taken after an {@code invokeAndWait} flush — the idiom {@code ChatPanelTest} and
 * {@code GuiPanelsSubscriptionLifecycleTest} established. Waits are bounded polls with an explicit
 * deadline (CLAUDE.md rule: no untimed waits).
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
        assertTrue(overlay.isShowingAStep(), "the final scripted step must be shown before it clears");

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
