package it.unige.portcommand.util;

import java.util.List;

/**
 * Test-only accessor for {@link EventBus}'s package-private introspection. Lives in the SAME
 * package as {@link EventBus} (under the test source set, so it ships in no artifact) rather
 * than adding a public method to the production class — the task-03 stub deliberately does not
 * dispatch to subscribers yet (real dispatch arrives in task 17), so tests that need to see what
 * a behaviour published read the bus's recorded queue directly instead.
 */
public final class EventBusProbe {

    private EventBusProbe() {
    }

    public static List<Event> published(EventBus bus) {
        return bus.publishedEvents();
    }
}
