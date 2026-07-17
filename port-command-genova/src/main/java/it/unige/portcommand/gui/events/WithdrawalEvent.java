package it.unige.portcommand.gui.events;

import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.util.Event;

/**
 * A vessel withdrew from negotiation without a deal — matches
 * {@link Deal.Outcome} (task 02) rather than an ad-hoc reason string; expected
 * values are {@code WITHDRAW_PRICE} / {@code TIMEOUT} / {@code PLAYER_REFUSED}
 * (never {@code DEAL} — that is {@link DealClosedEvent}'s job). See
 * {@link NegotiationClosedEvent}'s javadoc for how the three events relate.
 *
 * <p>{@code simTimeMillis} was added by task 20 (adversarial-review finding): the reputation
 * ledger buckets its history by game day, derived from an event's own sim time, and this event
 * was the one money/reputation signal carrying no timestamp — so every withdrawal penalty
 * stamped day 1 forever. {@link DealClosedEvent} has never had the problem; it gets its stamp
 * from the wrapped {@code Deal.closedAtSimMillis()}.
 *
 * @param vesselId      the vessel that withdrew
 * @param outcome       WITHDRAW_PRICE / TIMEOUT / PLAYER_REFUSED
 * @param simTimeMillis sim time the withdrawal closed
 */
public record WithdrawalEvent(String vesselId, Deal.Outcome outcome, long simTimeMillis) implements Event {
}
