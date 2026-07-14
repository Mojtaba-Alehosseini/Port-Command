package it.unige.portcommand.util;

import it.unige.portcommand.gui.events.SimClockTickEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for the task-03 {@link EventBus} stub: publish records events for
 * inspection; subscribe/cancel manage subscribers; the stub deliberately does
 * NOT dispatch (real async/EDT dispatch is task 17).
 */
class EventBusTest {

    @Test
    void publishStoresEventForInspection() {
        EventBus bus = new EventBus();
        SimClockTickEvent e = new SimClockTickEvent(60_000, 1, 0, 1);
        bus.publish(e);
        assertEquals(1, bus.publishedEvents().size());
        assertSame(e, bus.publishedEvents().get(0));
    }

    @Test
    void subscribeRegistersAndCancelRemoves() {
        EventBus bus = new EventBus();
        Subscription<SimClockTickEvent> sub =
                bus.subscribe(SimClockTickEvent.class, e -> { }, DeliveryMode.ASYNC);
        assertEquals(1, bus.subscriberCount(SimClockTickEvent.class));
        sub.cancel();
        assertEquals(0, bus.subscriberCount(SimClockTickEvent.class));
    }

    @Test
    void stubDoesNotDispatchToSubscribers() {
        EventBus bus = new EventBus();
        AtomicInteger hits = new AtomicInteger();
        bus.subscribe(SimClockTickEvent.class, e -> hits.incrementAndGet(), DeliveryMode.CALLER_THREAD);
        bus.publish(new SimClockTickEvent(0, 1, 0, 0));
        assertEquals(0, hits.get(), "task-03 stub must not dispatch yet (task 17 owns dispatch)");
    }
}
