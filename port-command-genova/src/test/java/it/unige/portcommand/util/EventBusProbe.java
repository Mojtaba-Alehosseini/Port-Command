package it.unige.portcommand.util;

import java.util.List;

/**
 * Test-only accessor for {@link EventBus}'s package-private introspection. Lives in the SAME
 * package as {@link EventBus} (under the test source set, so it ships in no artifact) rather
 * than adding a public method to the production class. {@link EventBus} now dispatches for real
 * (task 17), but it still keeps an append-only audit log of every published event alongside real
 * subscriber delivery — several task-10/11 integration tests poll this log instead of (or in
 * addition to) subscribing, and that pattern keeps working unmodified.
 */
public final class EventBusProbe {

    private EventBusProbe() {
    }

    public static List<Event> published(EventBus bus) {
        return bus.publishedEvents();
    }
}
