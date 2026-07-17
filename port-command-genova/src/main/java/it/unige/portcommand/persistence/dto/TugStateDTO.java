package it.unige.portcommand.persistence.dto;

import it.unige.portcommand.ontology.Position;

/**
 * One tug's persisted state (task 22). {@code pendingBids} are deliberately NOT saved:
 * an un-awarded Contract Net dies with the save (its vessel is persisted tug-less at
 * AWAITING_TUG and the rebuilt HarbourMaster re-runs the CNP), so a restored bid could
 * only ever be answered by a session that no longer exists.
 *
 * @param tugId          {@code tug_1}..{@code tug_4}
 * @param status         {@code TugStatus} name; BIDDING is normalised to IDLE at snapshot
 * @param position       live position (pixels — the map/Position convention)
 * @param fuelState      0..1 tank fraction
 * @param job            the escort in progress, or null when idle. {@code client} (an AID)
 *                       is not persisted — it is always the HarbourMaster and is
 *                       reconstructed by local name on restore
 * @param pickupTarget   the vessel pickup point while EN_ROUTE_TO_VESSEL, else null
 */
public record TugStateDTO(
        String tugId,
        String status,
        Position position,
        double fuelState,
        TugJobDTO job,
        Position pickupTarget) {

    /** {@code TugJob} minus the AID (rebuilt as {@code harbour_master} on restore). */
    public record TugJobDTO(String vesselId, Position berthPosition, String conversationId) {
    }
}
