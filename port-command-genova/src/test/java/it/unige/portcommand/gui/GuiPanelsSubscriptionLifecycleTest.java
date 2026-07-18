package it.unige.portcommand.gui;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.swing.SwingUtilities;

import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves every real panel's subscribe-in-constructor / cancel-in-{@code removeNotify()} lifecycle
 * (planning/17 hard constraint + acceptance criterion, INVARIANTS.md), now asserted against each
 * panel's actual model state instead of a placeholder counter: a positive control (the
 * constructor's subscription really is live, proven via the model) paired with the actual leak
 * guard (the model stops changing after {@code removeNotify()}). {@code removeNotify()} is called
 * directly — these are plain {@code JPanel}s, never added to a realized {@code Window}, so no
 * display is touched (a {@code JFrame} would throw under headless; a {@code JPanel} does not — see
 * build.gradle.kts's {@code java.awt.headless=true} test config).
 *
 * <p>{@code MapPanel} gets TWO lifecycle tests, not one: its {@link EventBus} subscriptions
 * (weather/vessel arrival/departure) cancel exactly like every other panel's, but its
 * {@link PortStateArtifact} subscription (berths/tugs) has no cancel API at all — that channel's
 * test proves the panel's own {@code active} flag ("poor-man's unsubscribe") stops it from acting
 * on further deltas instead, per {@code MapPanel}'s own javadoc.
 */
class GuiPanelsSubscriptionLifecycleTest {

    @Test
    @Timeout(5)
    void mapPanelPortStateArtifactChannelStopsUpdatingModelAfterRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        PortStateArtifact portState = new PortStateArtifact();
        MapPanel panel = new MapPanel(bus, portState);

        // Probe ids OUTSIDE the seeded fleet (tug_1..tug_4 are seeded at construction), so
        // containsKey is meaningful. PortStateArtifact.emit() calls subscribers synchronously on
        // the CALLING thread (never the EDT) -- asserting the model has NOT changed yet, before any
        // invokeAndWait flush, proves MapPanel's subscriber lambda really does hop to the EDT rather
        // than mutating inline (a no-op invokeLater wrap would make this assertion meaningless).
        portState.updateTug("tug_probe_a", new Position(10, 20, 0), TugStatus.IDLE);
        assertFalse(panel.model().tugs().containsKey("tug_probe_a"),
                "model must not update synchronously on the calling (non-EDT) thread");

        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(panel.model().tugs().containsKey("tug_probe_a"),
                "constructor subscription must be live once the EDT drains (positive control)");

        panel.removeNotify();
        portState.updateTug("tug_probe_b", new Position(99, 99, 0), TugStatus.IDLE);
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(panel.model().tugs().containsKey("tug_probe_b"),
                "removeNotify() must stop the panel acting on further PortStateArtifact deltas");
    }

    @Test
    @Timeout(5)
    void mapPanelWeatherEventBusSubscriptionCancelsOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        MapPanel panel = new MapPanel(bus, new PortStateArtifact());

        bus.publish(new WeatherChangeEvent(22.0, "good", 1.0, "sunny", false));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(22.0, panel.model().weather().orElseThrow().windKnots(), 0.0001,
                "constructor subscription must be live (positive control)");

        panel.removeNotify();
        bus.publish(new WeatherChangeEvent(40.0, "poor", 3.0, "stormy", true));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(22.0, panel.model().weather().orElseThrow().windKnots(), 0.0001,
                "removeNotify() must cancel the EventBus subscription");
    }

    @Test
    @Timeout(5)
    void chatPanelUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus, (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")));
        WalkInDialogueSnapshot snapshot = WalkInDialogueSnapshot.of("W001",
                new VesselSpec("W001", "cargo_vessel", 9.0, 150.0, 30000, "general_cargo", 0L),
                "berth_1", 6, 1500.0, 0.0, 1, 0.0, false);

        bus.publish(new NegotiationOpenedEvent("dlg-1", snapshot));
        SwingUtilities.invokeAndWait(() -> { });
        bus.publish(new AssistantChatEvent("dlg-1", "hello"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(3, panel.registry().tab("dlg-1").transcript().size(),
                "constructor subscription must be live (positive control): orientation line + opening bubble + assistant reply");

        panel.removeNotify();
        bus.publish(new AssistantChatEvent("dlg-1", "world"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(3, panel.registry().tab("dlg-1").transcript().size(),
                "removeNotify() must cancel the subscription");
    }

    @Test
    @Timeout(5)
    void commLogPanelUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = new CommLogPanel(bus);

        bus.publish(new CommLogEvent(0L, "hm", List.of("v1"), 7, "Requesting berth", "conv-1"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.model().size(), "constructor subscription must be live (positive control)");

        panel.removeNotify();
        bus.publish(new CommLogEvent(1L, "hm", List.of("v1"), 7, "Requesting berth", "conv-2"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.model().size(), "removeNotify() must cancel the subscription");
    }

    /**
     * Checkpoint-#6 F4 (fixed 2026-07-18): the comm log clean-slates on the loaded-marker
     * event, like the chat tabs. Wiring is proven hermetically on an EMPTY model (clear()
     * archives nothing, so the fast lane touches no disk): the loaded-event handler runs
     * {@code render()}, whose auto-scroll flag flipping from its initial {@code false} is the
     * observable. {@code CommLogModelTest} owns the clear-with-entries semantics (temp-dir
     * archiver); the drop itself is asserted on the post-removeNotify leg here.
     */
    @Test
    @Timeout(5)
    void commLogPanelClearsOnGameLoadedEventAndUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = new CommLogPanel(bus);
        SwingUtilities.invokeAndWait(() -> panel.setAtBottomOverrideForTest(true));
        assertFalse(panel.lastRenderAutoScrolledForTest(), "precondition: no render has run yet");

        bus.publish(new it.unige.portcommand.persistence.events.GameLoadedEvent(1, 44_800.0, 62));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(panel.lastRenderAutoScrolledForTest(),
                "GameLoadedEvent must drive the clean-slate render (subscription live)");
        assertEquals(0, panel.model().size(), "the log is empty after the load marker");

        // Detached panel: the loaded-marker subscription must be cancelled too, or a stale
        // panel would clear (and archive) a live model's entries.
        panel.removeNotify();
        bus.publish(new CommLogEvent(2L, "hm", List.of("v1"), 7, "Requesting berth", "conv-3"));
        bus.publish(new it.unige.portcommand.persistence.events.GameLoadedEvent(2, 0.0, 0));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, panel.model().size(),
                "after removeNotify() neither the entry nor the clear may land — both cancelled");
    }

    @Test
    @Timeout(5)
    void hudPanelUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        HUDPanel panel = new HUDPanel(bus);

        bus.publish(new SimClockTickEvent(0, 1, 0, 0));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, panel.model().simMinute(), "constructor subscription must be live (positive control)");

        bus.publish(new SimClockTickEvent(60_000, 1, 0, 1));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.model().simMinute(), "sanity: the subscription tracks live ticks before detach");

        panel.removeNotify();
        bus.publish(new SimClockTickEvent(120_000, 1, 0, 2));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.model().simMinute(), "removeNotify() must cancel the subscription");
    }

    @Test
    @Timeout(5)
    void notificationStripUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        NotificationStrip panel = new NotificationStrip(bus);

        bus.publish(new NotificationEvent("test", NotificationEvent.Severity.INFO, 0L));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.model().size(), "constructor subscription must be live (positive control)");

        panel.removeNotify();
        bus.publish(new NotificationEvent("test2", NotificationEvent.Severity.INFO, 0L));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.model().size(), "removeNotify() must cancel the subscription");
    }
}
