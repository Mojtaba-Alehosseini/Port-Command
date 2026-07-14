package it.unige.portcommand.agents;

/**
 * Lifecycle state of a tug. Declared here (task 06) as a bare enum so the
 * {@code PortStateArtifact.updateTug(String, Position, TugStatus)} API — which
 * passes position separately — is complete before task 08. The tug behaviours
 * that drive these transitions are owned by task 08; this enum is the agreed
 * shape (the separate {@code Position} argument in {@code updateTug} means the
 * status itself carries no position).
 */
public enum TugStatus {
    IDLE,
    BIDDING,
    EN_ROUTE_TO_VESSEL,
    ESCORTING,
    RETURNING,
    REFUELING
}
