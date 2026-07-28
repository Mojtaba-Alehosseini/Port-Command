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
 * <p>{@code playerEngaged} was added by task 24 (visual checkpoint #5, Moji's balance call):
 * whether the player took at least one negotiation action in this dialogue (a counter — computed
 * vessel-side as {@code WalkInState.round() > 1}, which is set synchronously when the player's
 * counter is recorded, so it is race-free). It only changes the delta for {@code TIMEOUT}: a
 * never-engaged walk-in that times out costs −1, an engaged-then-abandoned one costs −3 (the
 * split lives in {@code ReputationRules.withdrawalDelta}). For every other outcome it is inert —
 * {@code WITHDRAW_PRICE}/{@code WITHDRAW_DURATION} necessarily engaged (the engine only evaluates
 * a player counter), {@code PLAYER_REFUSED} is 0 regardless.
 *
 * @param vesselId      the vessel that withdrew
 * @param outcome       WITHDRAW_PRICE / WITHDRAW_DURATION / TIMEOUT / PLAYER_REFUSED
 * @param playerEngaged whether the player ever countered in this dialogue (splits TIMEOUT only)
 * @param simTimeMillis sim time the withdrawal closed
 */
public record WithdrawalEvent(String vesselId, Deal.Outcome outcome, boolean playerEngaged,
                              long simTimeMillis) implements Event {
}
