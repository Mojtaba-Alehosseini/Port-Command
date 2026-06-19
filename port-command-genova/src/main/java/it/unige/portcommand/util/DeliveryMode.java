package it.unige.portcommand.util;

/**
 * How the {@link EventBus} delivers an event to a subscriber. Dispatch semantics
 * are implemented in task 17; the task-03 stub records the mode but does not act
 * on it.
 */
public enum DeliveryMode {

    /** Deliver on the Swing Event Dispatch Thread (GUI subscribers). */
    EDT,

    /** Deliver on a background executor. */
    ASYNC,

    /** Deliver synchronously on the publishing thread. */
    CALLER_THREAD
}
