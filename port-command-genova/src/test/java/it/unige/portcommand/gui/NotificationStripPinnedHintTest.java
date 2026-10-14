package it.unige.portcommand.gui;

import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.util.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S18b (UI tree U13): "the 'No game running' chip vanishes after 8 s". The empty shell's hint is
 * pinned: it stays until it is taken away, and it is not one of the chips.
 */
class NotificationStripPinnedHintTest {

    @Test
    @Timeout(10)
    void aPinnedHintStaysAndIsNotAChip() throws Exception {
        EventBus bus = new EventBus();
        NotificationStrip[] strip = new NotificationStrip[1];
        SwingUtilities.invokeAndWait(() -> strip[0] = new NotificationStrip(bus, 50L));

        SwingUtilities.invokeAndWait(() -> strip[0].showPinnedHint("No game running"));
        bus.publish(new NotificationEvent("a chip", NotificationEvent.Severity.INFO, 0L));
        SwingUtilities.invokeAndWait(() -> { });
        long deadline = System.currentTimeMillis() + 5_000;
        while (strip[0].model().size() > 0 && System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait(() -> { }); // the chip's 50 ms timer fires on the EDT
        }
        assertEquals(0, strip[0].model().size(), "the chip has gone by itself");

        SwingUtilities.invokeAndWait(() -> assertEquals("[i] No game running", strip[0].pinnedHintForTest(),
                "the pinned hint has not"));
        SwingUtilities.invokeAndWait(() -> strip[0].showPinnedHint(null));
        SwingUtilities.invokeAndWait(() -> assertEquals("", strip[0].pinnedHintForTest()));
    }
}
