package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * A vessel has left the harbour ({@code DepartBehaviour}'s {@code departed}
 * INFORM, task 07). No producer exists yet — a future task wires the HM's
 * receipt of that INFORM to also publish this, for the map (task 18) to drop
 * the marker.
 *
 * @param vesselId the vessel's id
 */
public record VesselDepartedEvent(String vesselId) implements Event {
}
