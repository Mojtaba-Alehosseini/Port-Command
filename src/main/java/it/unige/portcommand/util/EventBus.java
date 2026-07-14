package it.unige.portcommand.util;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder publish/subscribe bus. Task 17 replaces this with the real
 * async, EDT-aware dispatcher; the public surface here — {@link #publish(Event)}
 * and {@link #subscribe(Class, Consumer, DeliveryMode)} returning a
 * {@link Subscription} — is kept identical so callers written against the stub
 * never change.
 *
 * <p>Stub behaviour: {@code publish} logs at DEBUG and records the event so a unit
 * test can read what was published; {@code subscribe} stores the handler and
 * returns a working {@code Subscription} but does <strong>not</strong> dispatch.
 * No string channels — events are typed records implementing {@link Event}.
 */
public final class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Queue<Event> published = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Class<? extends Event>, CopyOnWriteArrayList<Consumer<? extends Event>>> subscribers =
            new ConcurrentHashMap<>();

    /** Records the event and logs it. The task-17 impl will dispatch to subscribers. */
    public void publish(Event event) {
        published.add(event);
        log.debug("EventBus.publish {} (stub: stored, dispatch arrives in task 17)",
                event.getClass().getSimpleName());
    }

    /**
     * Registers a handler and returns a {@link Subscription}. The stub does not
     * deliver events; it only tracks subscribers so the API is stable.
     */
    public <E extends Event> Subscription<E> subscribe(Class<E> type, Consumer<E> handler, DeliveryMode mode) {
        CopyOnWriteArrayList<Consumer<? extends Event>> list =
                subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        list.add(handler);
        log.debug("EventBus.subscribe {} mode={} (stub: stored, no dispatch yet)",
                type.getSimpleName(), mode);
        return new Subscription<>(() -> list.remove(handler));
    }

    /** Test-only (package-private): the events published so far, in order. */
    List<Event> publishedEvents() {
        return List.copyOf(published);
    }

    /** Test-only (package-private): number of live subscribers for an event type. */
    int subscriberCount(Class<? extends Event> type) {
        CopyOnWriteArrayList<Consumer<? extends Event>> list = subscribers.get(type);
        return list == null ? 0 : list.size();
    }
}
