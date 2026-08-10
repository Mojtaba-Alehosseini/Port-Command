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

    /**
     * Audit P1-9 follow-up (2026-08-10 adversarial review): the countdown must never REWIND when
     * a refresh re-derives its origin from a snapshot the HarbourMaster stamped earlier.
     *
     * <p>{@code refresh()} runs on many paths that carry no new snapshot — every player send, an
     * assistant reply, a settings change, a system line. The first cut of the P1-9 re-anchoring
     * assigned {@code openedAtSimMillis = now - elapsed(snapshot.timeUsedSec())} outright, so each
     * of those reset elapsed time to the last snapshot's figure while the clock kept running: the
     * strip jumped BACKWARDS and then froze there until the vessel next countered. Measured
     * 60 -> 35 -> 20, then back to 35 and stuck. The anchor is monotone now.
     */
    @Test
    @Timeout(5)
    void aRefreshCarryingAStaleSnapshotNeverRewindsTheCountdown() throws Exception {
        EventBus bus = new EventBus();
        SimClock clock = new SimClock(300);
        ChatPanel panel = new ChatPanel(bus,
                (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")), clock);
        bus.publish(openEvent("d1", "V1"));
        SwingUtilities.invokeAndWait(() -> { });
        DialogueTabView view = panel.viewForTest("d1");

        // The vessel counters 25 real-seconds in, carrying the HM's elapsed figure.
        clock.advance(25_000);
        WalkInDialogueSnapshot atRound2 = new WalkInDialogueSnapshot("V1", "cargo_vessel", 9.0, 150.0,
                30000, "general_cargo", "berth_1", 6, 1400.0, 0.0, 2, 25.0, false, "Genoa Star");
        bus.publish(new NegotiationOpenedEvent("d1", atRound2));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(view.offerStripTextForTest().contains("~35s remaining"),
                "the HM's own elapsed figure anchors the countdown, got: " + view.offerStripTextForTest());

        // 15 more seconds pass, then something refreshes the tab WITHOUT a new snapshot.
        clock.advance(15_000);
        tick(bus, clock);
        assertTrue(view.offerStripTextForTest().contains("~20s remaining"),
                "ticking down normally, got: " + view.offerStripTextForTest());

        SwingUtilities.invokeAndWait(view::refresh); // the stale-snapshot re-derivation path
        assertTrue(view.offerStripTextForTest().contains("~20s remaining"),
                "a refresh carrying the SAME snapshot must not rewind the countdown, got: "
                        + view.offerStripTextForTest());

        clock.advance(10_000);
        tick(bus, clock);
        assertTrue(view.offerStripTextForTest().contains("~10s remaining"),
                "and it must keep running afterwards rather than freezing, got: "
                        + view.offerStripTextForTest());
    }
}
