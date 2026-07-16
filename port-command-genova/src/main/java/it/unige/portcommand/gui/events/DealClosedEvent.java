package it.unige.portcommand.gui.events;

import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.util.Event;

/**
 * A deal was struck — wraps the canonical {@link Deal} (task 02) directly
 * rather than duplicating its fields, so the HUD (task 20) reads
 * {@code deal.finalPrice()}/{@code deal.finalHours()}/{@code deal.outcome()}
 * to compute its wallet + reputation deltas. Expected only for
 * {@code deal.outcome() == Deal.Outcome.DEAL} — a non-{@code DEAL} outcome is
 * {@link WithdrawalEvent}'s job, not this one's. See
 * {@link NegotiationClosedEvent}'s javadoc for how the three events relate.
 *
 * @param deal the closed deal
 */
public record DealClosedEvent(Deal deal) implements Event {
}
