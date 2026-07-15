package it.unige.portcommand.harbourmaster;

/**
 * Seam between the HarbourMaster's auto-flow dispatch and the tug Contract Net.
 * {@link StubCnpCoordinator} (this task) implements the real CFP/collect/award
 * cycle; task 15 replaces it with {@code RealCnpCoordinator} (adds zero-bid retry,
 * Settings-driven timing, post-ACCEPT-failure handling).
 */
public interface CnpCoordinator {

    /**
     * Starts a Contract Net for tug escort. Implementations must add a
     * {@code Behaviour} rather than sending directly, so this is safe to call from
     * within another behaviour's {@code action()} on the same agent thread.
     */
    void initiate(CnpRequest req);
}
