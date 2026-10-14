package it.unige.portcommand.gui;

import java.util.List;

import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S18b (UI tree U12: "Notification strip hides the newest chips when they wrap"; playtest 2 PT10:
 * stale chips). Newest first, and a newer notice on a topic takes its older chip's place.
 */
class NotificationStripOrderTest {

    private static NotificationStrip strip(EventBus bus) throws Exception {
        NotificationStrip[] strip = new NotificationStrip[1];
        SwingUtilities.invokeAndWait(() -> strip[0] = new NotificationStrip(bus, 60_000L));
        return strip[0];
    }

    @Test
    @Timeout(10)
    void theNewestChipIsFirst() throws Exception {
        EventBus bus = new EventBus();
        NotificationStrip strip = strip(bus);
        bus.publish(new NotificationEvent("first", NotificationEvent.Severity.INFO, 0L));
        bus.publish(new NotificationEvent("second", NotificationEvent.Severity.INFO, 0L));
        SwingUtilities.invokeAndWait(() -> { });

        SwingUtilities.invokeAndWait(() -> assertEquals(List.of("[i] second", "[i] first"), strip.chipTextsForTest()));
    }

    @Test
    @Timeout(10)
    void theClearingTakesTheWorseningsPlace() throws Exception {
        EventBus bus = new EventBus();
        NotificationStrip strip = strip(bus);
        bus.publish(new NotificationEvent("Weather worsening: 38 kn", NotificationEvent.Severity.WARNING, 0L,
                NotificationEvent.WEATHER_TOPIC));
        bus.publish(new NotificationEvent("Berth 2 flooded", NotificationEvent.Severity.WARNING, 0L,
                NotificationEvent.berthTopic("berth_2")));
        bus.publish(new NotificationEvent("Weather clearing: 22 kn", NotificationEvent.Severity.INFO, 0L,
                NotificationEvent.WEATHER_TOPIC));
        SwingUtilities.invokeAndWait(() -> { });

        SwingUtilities.invokeAndWait(() -> assertEquals(List.of("[i] Weather clearing: 22 kn", "[!] Berth 2 flooded"),
                strip.chipTextsForTest()));
        SwingUtilities.invokeAndWait(() -> assertEquals(2, strip.model().size()));
    }
}
