package it.unige.portcommand.gui;

import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.events.AssistantChatEvent;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.VesselArrivedEvent;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves every stub panel's subscribe-in-constructor / cancel-in-{@code removeNotify()}
 * lifecycle (planning/17 hard constraint + acceptance criterion): a positive control
 * (the constructor's subscription really is live) paired with the actual leak guard
 * (it stops after {@code removeNotify()}). {@code removeNotify()} is called directly —
 * these are plain {@code JPanel}s, never added to a realized {@code Window}, so no
 * display is touched (a {@code JFrame} would throw under headless; a {@code JPanel}
 * does not — see build.gradle.kts's {@code java.awt.headless=true} test config).
 */
class GuiPanelsSubscriptionLifecycleTest {

    @Test
    @Timeout(5)
    void mapPanelUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        MapPanel panel = new MapPanel(bus);

        bus.publish(new VesselArrivedEvent("V1", "cargo_vessel", "contracted"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "constructor subscription must be live (positive control)");

        panel.removeNotify();
        bus.publish(new VesselArrivedEvent("V2", "tanker", "walk_in"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "removeNotify() must cancel the subscription");
    }

    @Test
    @Timeout(5)
    void chatPanelUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        ChatPanel panel = new ChatPanel(bus);

        bus.publish(new AssistantChatEvent("dlg-1", "hello"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "constructor subscription must be live (positive control)");

        panel.removeNotify();
        bus.publish(new AssistantChatEvent("dlg-1", "world"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "removeNotify() must cancel the subscription");
    }

    @Test
    @Timeout(5)
    void commLogPanelUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        CommLogPanel panel = new CommLogPanel(bus);

        bus.publish(new CommLogEvent(0L, "hm", java.util.List.of("v1"), 7, "Requesting berth", "conv-1"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "constructor subscription must be live (positive control)");

        panel.removeNotify();
        bus.publish(new CommLogEvent(1L, "hm", java.util.List.of("v1"), 7, "Requesting berth", "conv-2"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "removeNotify() must cancel the subscription");
    }

    @Test
    @Timeout(5)
    void hudPanelUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        HUDPanel panel = new HUDPanel(bus);

        bus.publish(new SimClockTickEvent(0, 1, 0, 0));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "constructor subscription must be live (positive control)");

        panel.removeNotify();
        bus.publish(new SimClockTickEvent(60_000, 1, 0, 1));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "removeNotify() must cancel the subscription");
    }

    @Test
    @Timeout(5)
    void notificationStripUnsubscribesOnRemoveNotify() throws Exception {
        EventBus bus = new EventBus();
        NotificationStrip panel = new NotificationStrip(bus);

        bus.publish(new NotificationEvent("test", NotificationEvent.Severity.INFO, 0L));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "constructor subscription must be live (positive control)");

        panel.removeNotify();
        bus.publish(new NotificationEvent("test2", NotificationEvent.Severity.INFO, 0L));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.receivedCountForTest(), "removeNotify() must cancel the subscription");
    }
}
