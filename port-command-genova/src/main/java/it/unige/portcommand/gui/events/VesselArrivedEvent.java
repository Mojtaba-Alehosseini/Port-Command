package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * A vessel has arrived and is now tracked by the HarbourMaster (contracted
 * auto-flow REQUEST accepted, or a walk-in's deal confirmed). No producer
 * exists yet — a future task wires this alongside {@code VesselTracking}
 * creation, for the map (task 18) to add a marker.
 *
 * @param vesselId   the vessel's id
 * @param vesselType one of the 5 canonical vessel types
 * @param channel    {@code "contracted"} | {@code "walk_in"}
 */
public record VesselArrivedEvent(String vesselId, String vesselType, String channel) implements Event {
}
