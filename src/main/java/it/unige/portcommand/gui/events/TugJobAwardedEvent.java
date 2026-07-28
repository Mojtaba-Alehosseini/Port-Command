package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * A tug won a Contract Net and was sent its {@code ACCEPT_PROPOSAL} — carrying the winning
 * bid's {@code cost}, which is otherwise DROPPED at the award boundary.
 *
 * <p>Created by task 20 (Moji's call at the task open) because nothing else carries the
 * awarded price: {@code BidInCNPBehaviour} computes it, {@code AwardContractBehaviour} reads it
 * into a {@code TugBid} and uses it for the Prolog {@code selectBestBids} decision, then builds
 * an {@code ACCEPT_PROPOSAL} containing only {@code vessel_id} + {@code berth_position}. The
 * later {@code escort_complete} INFORM correlates by {@code conversationId} but carries no cost
 * either. Publishing here is what keeps the Contract Net economically real — a cheaper winning
 * bid actually saves the port money.
 *
 * <p><strong>Charge-point: AWARD, not completion</strong> (decided 2026-07-17). The known cost:
 * a winning tug that has since committed elsewhere silently declines the ACCEPT
 * ({@code HandleAcceptRejectBehaviour}, no reply — {@code RealCnpCoordinator}'s documented
 * accepted exit ramp), so the port is charged a call-out for an escort that never runs.
 * Charging on completion instead would trade that rare over-charge for a free tug on the same
 * path, and would require making {@code escort_complete} functional (it is currently swallowed
 * by {@code ForwardWalkInToPlayerBehaviour}'s broad template).
 *
 * <p><strong>{@code conversationId} alone is NOT an idempotency key.</strong> Two co-winning
 * tugs for one vessel share a single CNP {@code cfpId}, so a ledger MUST dedupe on
 * {@code (conversationId, tugId)} together — dedupe on {@code conversationId} alone collapses
 * two real tug jobs into one and under-charges. A CNP retry mints a fresh {@code cfpId}, so it
 * would charge again; that is correct in principle (a new CNP is a new job) but cannot arise
 * today, because the only retry fires exclusively on ZERO bids and zero bids means no award
 * reached this event at all.
 *
 * @param vesselId       the vessel being escorted
 * @param tugId          the winning tug's local name — the ONLY per-tug discriminator available
 * @param cost           the winning bid's cost (€), verbatim from the tug's PROPOSE
 * @param conversationId the CNP {@code cfpId}; shared by co-winning tugs for the same vessel
 * @param simTimeMillis  sim time of the award
 */
public record TugJobAwardedEvent(String vesselId, String tugId, double cost, String conversationId,
                                  long simTimeMillis) implements Event {
}
