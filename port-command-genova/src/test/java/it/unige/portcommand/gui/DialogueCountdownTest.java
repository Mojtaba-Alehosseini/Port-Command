package it.unige.portcommand.gui;

import java.util.concurrent.CompletableFuture;

import javax.swing.SwingUtilities;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "~Ns remaining" countdown goes live with task 24: the strip converts the
 * sim time elapsed since the tab opened back to real seconds through the
 * clock's own mapping, refreshed on every {@link SimClockTickEvent}. Headless —
 * the {@code ChatPanelTest} publish-and-flush idiom.
 */
class DialogueCountdownTest {

    private static NegotiationOpenedEvent openEvent(String dialogueId, String vesselId) {
        WalkInDialogueSnapshot snapshot = new WalkInDialogueSnapshot(vesselId, "cargo_vessel", 9.0, 150.0,
                30000, "general_cargo", "berth_1", 6, 1500.0, 0.0, 1, 0.0, false, "Genoa Star");
        return new NegotiationOpenedEvent(dialogueId, snapshot);
    }

    private static void tick(EventBus bus, SimClock clock) throws Exception {
        bus.publish(new SimClockTickEvent(clock.nowSimMillis(), clock.gameDay(), clock.simHour(),
                clock.simMinute()));
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    @Timeout(5)
    void countdownStartsFullThenFollowsSimTimeDownToZero() throws Exception {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(300);
        ChatPanel panel = new ChatPanel(bus,
                (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")), clock);
        bus.publish(openEvent("d1", "V1"));
        SwingUtilities.invokeAndWait(() -> { });
        DialogueTabView view = panel.viewForTest("d1");

        assertTrue(view.offerStripTextForTest().contains("~60s remaining"),
                "full Settings.timeLimitSeconds window at open, got: " + view.offerStripTextForTest());

        clock.advance(30_000); // 30 real seconds elapse (in sim terms: 2.4 sim-hours)
        tick(bus, clock);
        assertTrue(view.offerStripTextForTest().contains("~30s remaining"),
                "half the window after 30 real-equivalent seconds, got: " + view.offerStripTextForTest());

        clock.advance(60_000); // way past the window
        tick(bus, clock);
        assertTrue(view.offerStripTextForTest().contains("~0s remaining"),
                "clamped at zero, got: " + view.offerStripTextForTest());
    }

    @Test
    @Timeout(5)
    void aPausedClockFreezesTheCountdown() throws Exception {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(300);
        ChatPanel panel = new ChatPanel(bus,
                (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")), clock);
        bus.publish(openEvent("d1", "V1"));
        SwingUtilities.invokeAndWait(() -> { });
        DialogueTabView view = panel.viewForTest("d1");

        clock.advance(10_000);
        tick(bus, clock);
        assertTrue(view.offerStripTextForTest().contains("~50s remaining"));

        clock.pause();
        clock.advance(40_000); // ignored while paused — the countdown must not move
        tick(bus, clock);
        assertTrue(view.offerStripTextForTest().contains("~50s remaining"),
                "paused game, frozen countdown, got: " + view.offerStripTextForTest());
    }
}
