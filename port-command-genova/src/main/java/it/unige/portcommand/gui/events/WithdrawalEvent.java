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
 * @param vesselId the vessel that withdrew
 * @param outcome  WITHDRAW_PRICE / TIMEOUT / PLAYER_REFUSED
 */
public record WithdrawalEvent(String vesselId, Deal.Outcome outcome) implements Event {
}
