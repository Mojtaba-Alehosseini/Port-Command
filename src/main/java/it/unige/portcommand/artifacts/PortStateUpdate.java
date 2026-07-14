package it.unige.portcommand.artifacts;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.ontology.Position;

/**
 * Delta emitted to {@link PortStateArtifact} subscribers — either a berth change
 * or a tug move. Sealed so subscribers pattern-match exhaustively (task 08's
 * escort behaviour reads {@link TugDelta} for the escorted vessel's position).
 */
public sealed interface PortStateUpdate permits PortStateUpdate.BerthDelta, PortStateUpdate.TugDelta {

    record BerthDelta(String berthId, BerthOccupancy occupancy) implements PortStateUpdate {
    }

    record TugDelta(String tugId, Position position, TugStatus status) implements PortStateUpdate {
    }
}
