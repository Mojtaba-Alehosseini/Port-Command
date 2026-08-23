package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * S9 (audit P1-8): a winning tug REFUSEd its award AFTER the charge-at-award landed —
 * published by the HarbourMaster's {@code HandleTugDeclineBehaviour} on receipt of the
 * protocol-tagged post-award REFUSE, consumed by {@code LedgerCoordinator} which posts the
 * COMPENSATING income entry ({@code IncomeRules.SOURCE_TUG_JOB_REFUND}, amount = the recorded
 * charge for {@code (conversationId, tugId)} — the coordinator remembers costs, so this event
 * carries none; the wire stays cost-free). Exactly-once mirrors the charge dedupe: a refund
 * needs a PRESENT charge key and an ABSENT refund key.
 *
 * <p>The €500 extra-tug surcharge credited when this tug made the escort's count exceed one is
 * deliberately NOT reversed: the shortfall re-tender replaces the tug, the vessel is still
 * escorted by the same total count, and the surcharge was earned for exactly that service.
 * (If the re-tender ultimately fails, the vessel holds via the zero-bid path and the surcharge
 * imbalance is the least of that day's problems — bounded, visible in the ledger.)
 *
 * @param vesselId       the escorted vessel whose tug declined
 * @param tugId          the declining tug
 * @param conversationId the AWARD's CNP conversation (the ledger key's first half)
 * @param reason         the tug's stated reason (busy / low_fuel / unknown_bid)
 * @param simTimeMillis  receipt time on the HarbourMaster clock
 */
public record TugJobDeclinedEvent(
        String vesselId,
        String tugId,
        String conversationId,
        String reason,
        long simTimeMillis) implements Event {
}
