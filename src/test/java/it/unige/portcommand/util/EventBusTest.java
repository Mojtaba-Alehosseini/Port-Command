package it.unige.portcommand.util;

import java.util.List;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.events.SimClockTickEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the real {@link EventBus} (task 17): publish still records every
 * event for {@link EventBusProbe} (task-10/11 integration tests poll this),
 * and now really dispatches to subscribers per {@link DeliveryMode}. Headless
 * throughout — these exercise {@link SwingUtilities}' EDT machinery directly,
 * never a visible {@code JFrame} (no display required, per planning/17).
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
    @Timeout(5)
    void callerThreadDispatchesSynchronouslyOnThePublishingThread() {
        EventBus bus = new EventBus();
        AtomicInteger hits = new AtomicInteger();
        bus.subscribe(SimClockTickEvent.class, e -> hits.incrementAndGet(), DeliveryMode.CALLER_THREAD);
        bus.publish(new SimClockTickEvent(0, 1, 0, 0));
        assertEquals(1, hits.get(), "CALLER_THREAD must deliver before publish() returns");
    }

    @Test
    @Timeout(5)
    void typedDispatchHasNoCrossTypeLeak() {
        EventBus bus = new EventBus();
        AtomicInteger pingHits = new AtomicInteger();
        AtomicInteger pongHits = new AtomicInteger();
        bus.subscribe(Ping.class, e -> pingHits.incrementAndGet(), DeliveryMode.CALLER_THREAD);
        bus.subscribe(Pong.class, e -> pongHits.incrementAndGet(), DeliveryMode.CALLER_THREAD);

        bus.publish(new Ping(1));

        assertEquals(1, pingHits.get(), "Ping subscriber must see the Ping");
        assertEquals(0, pongHits.get(), "Pong subscriber must never see a Ping (no cross-type leak)");
    }

    @Test
    @Timeout(5)
    void unsubscribeStopsDelivery() {
        EventBus bus = new EventBus();
        AtomicInteger hits = new AtomicInteger();
        Subscription<SimClockTickEvent> sub =
                bus.subscribe(SimClockTickEvent.class, e -> hits.incrementAndGet(), DeliveryMode.CALLER_THREAD);
        bus.publish(new SimClockTickEvent(0, 1, 0, 0));
        assertEquals(1, hits.get());

        sub.cancel();
        bus.publish(new SimClockTickEvent(60_000, 1, 0, 1));
        assertEquals(1, hits.get(), "a cancelled subscription must receive nothing further");
    }

    @Test
    @Timeout(5)
    void throwingSubscriberIsIsolatedFromOthersAndFromThePublisher() {
        EventBus bus = new EventBus();
        AtomicInteger secondHandlerHits = new AtomicInteger();
        bus.subscribe(SimClockTickEvent.class, e -> {
            throw new RuntimeException("boom — must not escape publish() or block the next subscriber");
        }, DeliveryMode.CALLER_THREAD);
        bus.subscribe(SimClockTickEvent.class, e -> secondHandlerHits.incrementAndGet(), DeliveryMode.CALLER_THREAD);

        bus.publish(new SimClockTickEvent(0, 1, 0, 0)); // must not throw out of this call

        assertEquals(1, secondHandlerHits.get(), "a throwing subscriber must not stop a sibling subscriber");
    }

    @Test
    @Timeout(5)
    void edtSubscriberAlwaysReceivesOnTheEventDispatchThread() throws Exception {
        EventBus bus = new EventBus();
        AtomicBoolean wasOnEdt = new AtomicBoolean(false);
        AtomicInteger hits = new AtomicInteger();
        bus.subscribe(SimClockTickEvent.class, e -> {
            wasOnEdt.set(SwingUtilities.isEventDispatchThread());
            hits.incrementAndGet();
        }, DeliveryMode.EDT);

        assertFalse(SwingUtilities.isEventDispatchThread(), "fixture sanity: the test thread is not the EDT");
        bus.publish(new SimClockTickEvent(0, 1, 0, 0));

        // EDT dispatch is posted via invokeLater, so it lands strictly after this
        // no-op we post from the test thread now (the EDT drains its queue FIFO) —
        // the standard "wait for pending EDT work" idiom; no sleep, no polling.
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(1, hits.get(), "the EDT subscriber must have run by now");
        assertTrue(wasOnEdt.get(), "SwingUtilities.isEventDispatchThread() must be true inside an EDT handler");
    }

    @Test
    @Timeout(5)
    void publishNeverBlocksOnASlowAsyncSubscriber() throws Exception {
        EventBus bus = new EventBus();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        bus.subscribe(SimClockTickEvent.class, e -> {
            handlerEntered.countDown();
            await(releaseHandler);
        }, DeliveryMode.ASYNC);

        long start = System.nanoTime();
        bus.publish(new SimClockTickEvent(0, 1, 0, 0));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 1000, "publish() must return immediately, not wait for the ASYNC handler; took "
                + elapsedMillis + "ms");
        assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "the ASYNC handler must still run eventually");
        releaseHandler.countDown(); // let the pool thread go so it doesn't strand for later tests
    }

    /**
     * Doesn't capture the WARN log directly (this codebase has no log-capturing test
     * infrastructure elsewhere either) — instead proves the drop-oldest BEHAVIOUR, which in
     * {@code SubscriberEntry.enqueue} is emitted by the exact same {@code if (dropped != null)}
     * branch that calls {@code log.warn(...)}: the two are the same code path, so observing the
     * drop is equivalent to observing the warn.
     */
    @Test
    @Timeout(10)
    void asyncOverflowDropsOldestWithWarnLog() throws Exception {
        EventBus bus = new EventBus();
        int capacity = EventBus.ASYNC_QUEUE_CAPACITY;
        int overflowBy = 5;
        int extra = capacity + overflowBy; // published after the blocker: fills the queue, then overflows by 5
        int totalPublished = 1 + extra; // + the one that blocks the drain loop
        int expectedDropped = overflowBy;
        int expectedDelivered = totalPublished - expectedDropped;

        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(expectedDelivered);
        List<Integer> received = Collections.synchronizedList(new CopyOnWriteArrayList<>());

        bus.subscribe(Counted.class, e -> {
            if (e.n() == 0) {
                blockerEntered.countDown();
                await(releaseBlocker); // freezes the drain loop so events 1..extra pile up in the queue
            }
            received.add(e.n());
            delivered.countDown();
        }, DeliveryMode.ASYNC);

        bus.publish(new Counted(0));
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS), "setup: the drain loop must pick up event 0 first");

        for (int n = 1; n <= extra; n++) {
            bus.publish(new Counted(n));
        }
        releaseBlocker.countDown();

        assertTrue(delivered.await(5, TimeUnit.SECONDS),
                "expected exactly " + expectedDelivered + " deliveries (capacity " + capacity + " + 1 blocker - "
                        + expectedDropped + " dropped), got " + received.size());
        assertEquals(expectedDelivered, received.size());
        assertTrue(received.contains(0), "the blocking event itself must have been delivered");
        for (int dropped = 1; dropped <= expectedDropped; dropped++) {
            assertFalse(received.contains(dropped), "event " + dropped + " should have been dropped as oldest");
        }
        for (int kept = expectedDropped + 1; kept <= extra; kept++) {
            assertTrue(received.contains(kept), "event " + kept + " should have survived the overflow");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record Ping(int n) implements Event {
    }

    private record Pong(int n) implements Event {
    }

    private record Counted(int n) implements Event {
    }
}
