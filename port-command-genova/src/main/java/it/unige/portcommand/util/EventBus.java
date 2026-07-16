package it.unige.portcommand.util;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Typed publish/subscribe bus for the whole app: agents publish from JADE
 * behaviour threads, GUI panels (task 18+) subscribe on the EDT, other
 * behaviours subscribe ASYNC. Replaces the task-03 stub — the public surface
 * ({@link #publish(Event)}, {@link #subscribe(Class, Consumer, DeliveryMode)}
 * returning a {@link Subscription}) is unchanged so every existing caller
 * compiles untouched; what changes is that {@code publish} now actually
 * invokes subscribers instead of only recording them.
 *
 * <p><b>Delivery modes</b> ({@link DeliveryMode}): {@code EDT} always dispatches
 * via {@link SwingUtilities#invokeLater} — even when {@code publish} is itself
 * called from the EDT — rather than invoking in-line when already on the EDT;
 * this trades one extra event-queue hop for never re-entering a handler while
 * an earlier delivery for the same event is still on the stack. {@code ASYNC}
 * buffers into a small bounded per-subscriber queue drained on a shared
 * 2-thread background pool (see {@link #ASYNC_EXECUTOR}'s javadoc for the
 * "handlers must not block" contract this implies); {@code CALLER_THREAD}
 * invokes the handler synchronously, in-line, on the publisher's own thread (a
 * slow {@code CALLER_THREAD} subscriber therefore blocks the publisher for
 * that subscriber's turn — the only mode where that happens; the tradeoff is
 * inherent to "direct call", not a bug). EDT and ASYNC never block the
 * publisher.
 *
 * <p><b>No cross-subscriber ordering guarantee.</b> Different subscribers of
 * the same event type may observe it in different orders relative to other
 * events (EDT interleaves with whatever else is queued on the EDT; ASYNC runs
 * on a shared pool). Delivery TO ONE subscriber is FIFO (its own queue/EDT
 * post order is preserved), but there is no cross-subscriber ordering
 * contract — do not write a handler that assumes it runs before/after another
 * subscriber's handler for the same event.
 *
 * <p><b>Isolation.</b> A subscriber that throws is caught and logged; it never
 * breaks the publisher or any other subscriber. Every publish is also kept in
 * an append-only audit log (unbounded — this list is a test/diagnostic seam,
 * see {@link EventBusProbe}, not consulted by production code) so tests that
 * predate real dispatch (several task-10/11 integration tests poll this list
 * instead of subscribing) keep working unmodified.
 */
public final class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    /** Per-subscriber ASYNC buffer size (planning/17 hard constraint). */
    static final int ASYNC_QUEUE_CAPACITY = 1024;

    // Shared across every EventBus instance rather than one pool per bus: many
    // tests construct short-lived buses (AssistantAgentIT alone makes ~9), and a
    // per-instance pool would leak idle daemon threads over a full suite run.
    // "A small ExecutorService" per the task spec — fixed at 2 threads, daemon so
    // it never holds the JVM open.
    //
    // CONTRACT (adversarial review finding, not yet violated by any live handler but worth
    // stating explicitly): an ASYNC handler must return promptly. drain() below holds one of
    // these 2 process-wide threads for the full duration of a subscriber's queued run; a
    // handler that blocks forever or infinite-loops permanently strands that thread, and two
    // such handlers (across ANY subscriber, on ANY bus instance — the pool is shared) would
    // starve ASYNC delivery everywhere for the rest of the JVM's life. Long-running or
    // potentially-blocking work belongs on its own thread/executor, dispatched FROM the
    // handler, not run synchronously inside it.
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "eventbus-async");
        t.setDaemon(true);
        return t;
    });

    private final Queue<Event> published = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Class<? extends Event>, CopyOnWriteArrayList<SubscriberEntry<?>>> subscribers =
            new ConcurrentHashMap<>();

    /**
     * Records the event, then dispatches it to every current subscriber of its
     * exact runtime type (no supertype/interface matching). Never blocks on a
     * subscriber's handler for EDT/ASYNC modes; see the class javadoc for
     * {@code CALLER_THREAD}.
     */
    public void publish(Event event) {
        Objects.requireNonNull(event, "event");
        published.add(event);
        List<SubscriberEntry<?>> entries = subscribers.get(event.getClass());
        if (entries == null || entries.isEmpty()) {
            log.debug("EventBus.publish {} (0 subscribers)", event.getClass().getSimpleName());
            return;
        }
        for (SubscriberEntry<?> entry : entries) {
            entry.deliver(event);
        }
    }

    /** Convenience overload defaulting to {@link DeliveryMode#EDT} — the common case for GUI panels. */
    public <E extends Event> Subscription<E> subscribe(Class<E> type, Consumer<E> handler) {
        return subscribe(type, handler, DeliveryMode.EDT);
    }

    /**
     * Registers {@code handler} for every future {@code publish} of exactly
     * {@code type}. Returns a {@link Subscription} the caller keeps and cancels
     * to stop delivery (e.g. a Swing panel in {@code removeNotify()}).
     */
    public <E extends Event> Subscription<E> subscribe(Class<E> type, Consumer<E> handler, DeliveryMode mode) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(mode, "mode");
        SubscriberEntry<E> entry = new SubscriberEntry<>(handler, mode);
        CopyOnWriteArrayList<SubscriberEntry<?>> list =
                subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        list.add(entry);
        log.debug("EventBus.subscribe {} mode={}", type.getSimpleName(), mode);
        return new Subscription<>(() -> list.remove(entry));
    }

    /** Test-only (package-private): every event ever published, in order. See {@link EventBusProbe}. */
    List<Event> publishedEvents() {
        return List.copyOf(published);
    }

    /** Test-only (package-private): number of live subscribers for an event type. */
    int subscriberCount(Class<? extends Event> type) {
        CopyOnWriteArrayList<SubscriberEntry<?>> list = subscribers.get(type);
        return list == null ? 0 : list.size();
    }

    /**
     * One subscriber's handler + delivery mode. EDT and {@code CALLER_THREAD}
     * dispatch directly; {@code ASYNC} buffers into its own bounded queue and
     * drains it on the shared executor with at most one drain task in flight at
     * a time per subscriber (a per-subscriber "serial executor" over the shared
     * pool — see Doug Lea's {@code java.util.concurrent.Executor} javadoc
     * example): this keeps one subscriber's own deliveries in order and
     * prevents two threads from ever calling the same handler concurrently,
     * while different subscribers still run in parallel on the pool.
     */
    private static final class SubscriberEntry<E extends Event> {
        private final Consumer<E> handler;
        private final DeliveryMode mode;
        private final BlockingQueue<Event> asyncQueue;
        private final AtomicBoolean draining = new AtomicBoolean(false);

        SubscriberEntry(Consumer<E> handler, DeliveryMode mode) {
            this.handler = handler;
            this.mode = mode;
            this.asyncQueue = mode == DeliveryMode.ASYNC ? new LinkedBlockingQueue<>(ASYNC_QUEUE_CAPACITY) : null;
        }

        void deliver(Event event) {
            switch (mode) {
                case EDT -> SwingUtilities.invokeLater(() -> invoke(event));
                case CALLER_THREAD -> invoke(event);
                case ASYNC -> enqueue(event);
            }
        }

        /** Bounded, non-blocking: full queue drops the oldest buffered event (+ WARN) to admit the new one. */
        private void enqueue(Event event) {
            while (!asyncQueue.offer(event)) {
                Event dropped = asyncQueue.poll();
                if (dropped != null) {
                    log.warn("EventBus ASYNC queue full (capacity {}); dropping oldest {} to admit {}",
                            ASYNC_QUEUE_CAPACITY, dropped.getClass().getSimpleName(),
                            event.getClass().getSimpleName());
                }
            }
            scheduleDrain();
        }

        private void scheduleDrain() {
            if (draining.compareAndSet(false, true)) {
                ASYNC_EXECUTOR.execute(this::drain);
            }
        }

        private void drain() {
            try {
                Event event;
                while ((event = asyncQueue.poll()) != null) {
                    invoke(event);
                }
            } finally {
                draining.set(false);
                // An offer() can land right after the loop above sees an empty queue but
                // before `draining` flips back to false; re-check so that event isn't
                // stranded un-drained until some unrelated future publish reschedules it.
                if (!asyncQueue.isEmpty()) {
                    scheduleDrain();
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void invoke(Event event) {
            try {
                handler.accept((E) event);
            } catch (RuntimeException e) {
                log.error("EventBus subscriber threw handling {} (mode={}) — isolated, other subscribers unaffected",
                        event.getClass().getSimpleName(), mode, e);
            }
        }
    }
}
