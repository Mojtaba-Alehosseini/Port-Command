package it.unige.portcommand.gui;

import java.util.List;

import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.util.EventBus;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless behaviour tests for {@link CommLogPanel}'s scroll-follow affordance. Follows the
 * established GUI-test idiom (see {@code GuiPanelsSubscriptionLifecycleTest}): real {@link EventBus},
 * construct the plain {@code JPanel} (never shown — tests run under {@code -Djava.awt.headless=true}),
 * publish {@link CommLogEvent}s, flush the EDT with {@code invokeAndWait(() -> { })}, and assert on
 * the panel's model / test-seams rather than on painted Swing state.
 *
 * <p>A never-laid-out scrollpane (no window) reports meaningless value/extent/maximum, so
 * {@code isAtBottom()} cannot be exercised through a real scroll headlessly. The panel exposes
 * {@code setAtBottomOverrideForTest(Boolean)} to simulate "the user scrolled up" — exactly the state
 * a real {@code AdjustmentListener} event would otherwise drive.
 */
class CommLogPanelTest {

    private static CommLogEvent event(long millis, String paraphrase) {
        return new CommLogEvent(millis, "hm", List.of("v1"), ACLMessage.INFORM, paraphrase, "conv-1");
    }

    @Test
    @Timeout(5)
    void scrolledUpSuppressesAutoScrollAndShowsResumeButtonWhileEntriesArrive() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = new CommLogPanel(bus);

        // Baseline "at bottom": a new entry auto-scrolls to the tail and the resume button is hidden.
        SwingUtilities.invokeAndWait(() -> panel.setAtBottomOverrideForTest(true));
        bus.publish(event(0L, "first"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.model().size(), "positive control: constructor subscription is live");
        assertTrue(panel.lastRenderAutoScrolledForTest(), "at bottom: render pins the view to the tail");
        assertFalse(panel.resumeButtonForTest().isVisible(), "at bottom: resume button hidden");

        // The user scrolls up: the resume button appears immediately.
        SwingUtilities.invokeAndWait(() -> panel.setAtBottomOverrideForTest(false));
        assertTrue(panel.resumeButtonForTest().isVisible(), "scrolled up: resume button visible");

        // New entries keep arriving; the view must NOT be yanked back down.
        bus.publish(event(1000L, "second"));
        bus.publish(event(2000L, "third"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(3, panel.model().size());
        assertFalse(panel.lastRenderAutoScrolledForTest(),
                "scrolled up: auto-scroll to bottom is suppressed while entries arrive");
        assertTrue(panel.resumeButtonForTest().isVisible(),
                "scrolled up: resume button stays visible as entries arrive");
    }

    @Test
    @Timeout(5)
    void clickingResumeReturnsToBottomAndHidesTheButton() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = new CommLogPanel(bus);

        // Enter the scrolled-up state with some content in the log.
        SwingUtilities.invokeAndWait(() -> panel.setAtBottomOverrideForTest(false));
        bus.publish(event(0L, "first"));
        bus.publish(event(1000L, "second"));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(panel.resumeButtonForTest().isVisible(), "precondition: scrolled up, button visible");

        // Click resume: it jumps to the tail and hides itself directly — even though the override
        // still reports "scrolled up" — so this proves the click handler acts, not merely isAtBottom().
        SwingUtilities.invokeAndWait(() -> panel.resumeButtonForTest().doClick(0));
        assertFalse(panel.resumeButtonForTest().isVisible(), "after resume: button hidden");
    }
}
