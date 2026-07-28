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
     * within another behaviour's {@code action()} on the same agent thread. Since task 24
     * (checkpoint-#5 B2) the real implementation SERIALIZES sessions — a call while one is in
     * flight queues until that session reports {@link #completed()}.
     */
    void initiate(CnpRequest req);

    /**
     * A Contract Net session reached a terminal state (awards sent, zero-bid hold, or no tugs
     * in DF) — the implementation may launch the next queued session. Called by
     * {@code InitiateCNPBehaviour}/{@code AwardContractBehaviour} on every terminal path.
     * Default no-op so test fakes only implementing {@link #initiate} keep compiling.
     */
    default void completed() {
    }
}
