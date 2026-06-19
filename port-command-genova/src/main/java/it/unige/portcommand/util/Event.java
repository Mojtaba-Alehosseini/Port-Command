package it.unige.portcommand.util;

/**
 * Marker interface for every {@link EventBus} event. Implemented by the typed
 * event records (the GUI events arrive in task 17; {@code SimClockTickEvent} in
 * task 03). No behaviour — it only constrains the bus to typed payloads.
 */
public interface Event {
}
