package it.unige.portcommand.agents;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable occupancy of one berth. Owned by {@code TerminalState}; published to
 * the {@code PortStateArtifact} and (later) seeded from scenario JSON by task 23,
 * so the components carry snake_case wire keys consistent with the task-02 DTOs.
 *
 * @param berthId          the berth this record describes
 * @param vesselId         occupying vessel, or {@code null} when {@link Status#FREE}
 * @param dockedAtSim      sim-millis the vessel docks (its ETA)
 * @param expectedFreeAtSim sim-millis cargo handling completes
 * @param craneId          assigned crane 1..cranesTotal, or 0 when free
 * @param status           FREE / PROVISIONAL / DOCKED
 */
public record BerthOccupancy(
        @JsonProperty("berth_id") String berthId,
        @JsonProperty("vessel_id") String vesselId,
        @JsonProperty("docked_at_sim") long dockedAtSim,
        @JsonProperty("expected_free_at_sim") long expectedFreeAtSim,
        @JsonProperty("crane_id") int craneId,
        @JsonProperty("status") Status status) {

    /** Lifecycle states of a berth (CLAUDE-locked terminal model). */
    public enum Status { FREE, PROVISIONAL, DOCKED }

    /** An empty berth. */
    public static BerthOccupancy free(String berthId) {
        return new BerthOccupancy(berthId, null, 0L, 0L, 0, Status.FREE);
    }

    /** A copy with a different status (the only mutation path; the record stays immutable). */
    public BerthOccupancy withStatus(Status newStatus) {
        return new BerthOccupancy(berthId, vesselId, dockedAtSim, expectedFreeAtSim, craneId, newStatus);
    }
}
