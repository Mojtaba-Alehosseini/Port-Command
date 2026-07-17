package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Customs granted a clearance — the €100 {@code customs_clearance} expense trigger (task 20).
 *
 * <p>Published from the HarbourMaster's customs-reply handler on the CONFIRM branch, and
 * deliberately published AFTER that handler's {@code pendingCustomsChecks.remove(...)} guard
 * consumes the conversation. That ordering is the idempotency: the raw {@code CommLogEvent} for
 * the same CONFIRM is published by {@code receiveLogged} BEFORE the guard runs, so a duplicate
 * CONFIRM would be correctly ignored by the handler yet still double-count for anything charging
 * off the comm-log firehose. Charging off this event instead moves the guarantee to the ledger
 * boundary rather than relying on {@code AnnounceArrivalBehaviour} being a {@code OneShotBehaviour}.
 *
 * <p>Scope note: customs is requested only for HAZMAT vessels and only on the CONTRACTED path
 * ({@code AutoFlowDispatcherBehaviour}'s {@code isHazmat} gate) — the walk-in path never requests
 * it (ADR-09's parity gap). So this fires far less often than "per clearance" suggests; the €100
 * is a real but small line in the day's variable costs.
 *
 * @param vesselId       the cleared vessel — recovered from the {@code "cust-"+vesselId}
 *                       conversation id, since the CONFIRM's own content carries no vessel id
 * @param clearanceRef   the customs reference (e.g. {@code "CL-2026-1"}) from the CONFIRM content
 * @param simTimeMillis  sim time of the clearance
 */
public record CustomsClearedEvent(String vesselId, String clearanceRef, long simTimeMillis) implements Event {
}
