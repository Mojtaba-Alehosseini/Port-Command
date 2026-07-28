package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * A contracted vessel was granted its berth at the contract's agreed fee — the contracted
 * path's counterpart to the walk-in path's {@link DealClosedEvent}.
 *
 * <p>Created by task 20 (Moji's call at the task open) to close a real asymmetry:
 * {@code DealClosedEvent} fires ONLY from {@code ForwardWalkInToPlayerBehaviour}, so before
 * this event the contracted fees in {@code contracts.json} (€5200 + €1800) reached the wire in
 * {@code AutoFlowDispatcherBehaviour}'s {@code ACCEPT_PROPOSAL} and were then observable only
 * as a paraphrase string inside a {@code CommLogEvent} — the wallet could never earn them.
 *
 * <p>Fired at the BERTH GRANT, which is the contracted path's binding commitment (the
 * Prolog-gated {@code ACCEPT_PROPOSAL}), not at service-completion or departure — those
 * INFORMs exist but are unhandled, and a granted contract is owed regardless. {@code contractId}
 * is the ledger's idempotency key: {@code AnnounceArrivalBehaviour} is a {@code OneShotBehaviour}
 * so one vessel sends one {@code request_berth}, but keying the charge makes that a ledger
 * guarantee rather than an upstream coincidence.
 *
 * @param contractId     the {@code ServiceContract} id — the ledger's idempotency key
 * @param vesselId       the contracted vessel
 * @param berthId        the granted berth
 * @param fee            the contract's agreed fee (€) — {@code ServiceContract.contractedFee()}
 * @param hours          the contract's agreed stay ({@code contractedHours()})
 * @param simTimeMillis  sim time of the grant
 */
public record ContractedFeeEarnedEvent(String contractId, String vesselId, String berthId, double fee,
                                        int hours, long simTimeMillis) implements Event {
}
