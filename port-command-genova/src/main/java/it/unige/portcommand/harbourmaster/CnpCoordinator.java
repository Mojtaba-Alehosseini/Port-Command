package it.unige.portcommand.harbourmaster;

/**
 * Seam between the HarbourMaster's auto-flow dispatch and the tug Contract Net.
 * {@link RealCnpCoordinator} implements the real CFP/collect/award cycle (task 11/ADR-08), with
 * zero-bid retry and Settings-driven timing added by task 15; post-ACCEPT-failure handling
 * (a winning tug REFUSEs) remains the one documented, accepted exit ramp.
 */
public interface CnpCoordinator {

    /**
     * Starts a Contract Net for tug escort. Implementations must add a
     * {@code Behaviour} rather than sending directly, so this is safe to call from
     * within another behaviour's {@code action()} on the same agent thread.
     */
    void initiate(CnpRequest req);
}
