package it.unige.portcommand.artifacts;

import it.unige.portcommand.agents.BerthOccupancy;

/**
 * Typed berth delta handed to {@link PortStateArtifact#update(BerthOccupancyUpdate)}.
 * The single berth-update payload — callers never pass a bare {@code BerthOccupancy}.
 */
public record BerthOccupancyUpdate(String berthId, BerthOccupancy occupancy) {
}
