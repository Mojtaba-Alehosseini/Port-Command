package it.unige.portcommand.util;

import java.util.Objects;

/**
 * Handle returned by {@link EventBus#subscribe}. Callers keep it and invoke
 * {@link #cancel()} to stop further deliveries (e.g. a Swing panel cancels in
 * {@code removeNotify()} so it does not leak after being detached).
 *
 * @param <E> the subscribed event type
 */
public final class Subscription<E extends Event> {

    private final Runnable canceller;

    Subscription(Runnable canceller) {
        this.canceller = Objects.requireNonNull(canceller, "canceller");
    }

    /** Removes the subscriber. Idempotent at the bus level (removing twice is harmless). */
    public void cancel() {
        canceller.run();
    }
}
