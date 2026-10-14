package it.unige.portcommand.persistence;

import java.nio.file.Path;
import java.util.List;

import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.lifecycle.events.AutosaveRequestedEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S18b (REMAINING_AREAS_DECISIONS P5): "Autosave is silent; failures only logged." A failed
 * autosave is said in the notification strip; the game goes on.
 */
class AutosaveCoordinatorFailureTest {

    @Test
    void aFailedAutosaveIsSaidNotOnlyLogged(@TempDir Path dir) {
        EventBus bus = new EventBus();
        AutosaveCoordinator coordinator = new AutosaveCoordinator(bus, new SaveLoadManager(dir), () -> {
            throw new IllegalStateException("harbour_master not in AgentDirectory");
        });

        coordinator.onAutosaveRequested(new AutosaveRequestedEvent(2));

        List<NotificationEvent> said = EventBusProbe.published(bus).stream()
                .filter(NotificationEvent.class::isInstance).map(NotificationEvent.class::cast).toList();
        assertEquals(1, said.size(), "one notice, and the call did not throw");
        assertEquals("Autosave failed: harbour_master not in AgentDirectory", said.get(0).text());
        assertEquals(NotificationEvent.Severity.WARNING, said.get(0).severity());
        coordinator.close();
    }

    @Test
    void aFailureWithoutAMessageNamesItsKind() {
        assertEquals("NullPointerException", AutosaveCoordinator.reasonOf(new NullPointerException()));
    }
}
