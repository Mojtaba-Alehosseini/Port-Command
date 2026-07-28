package it.unige.portcommand.gui.events;

import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.util.Event;

/**
 * A walk-in dialogue terminated, deal or not — pairs with
 * {@link NegotiationOpenedEvent} to bracket the ChatPanel's (task 19) tab
 * lifecycle. Fires for all four {@link Deal.Outcome} values; {@link DealClosedEvent}
 * / {@link WithdrawalEvent} fire ALONGSIDE it (not instead of it) as the more specific
 * HUD-facing signal for the {@code DEAL} / non-{@code DEAL} case respectively. Reuses
 * the canonical {@link Deal.Outcome} (task 02) rather than an ad-hoc status string.
 *
 * @param dialogueId the negotiation's conversation id
 * @param vesselId   the vessel that was negotiating
 * @param outcome    DEAL / WITHDRAW_PRICE / TIMEOUT / PLAYER_REFUSED
 */
public record NegotiationClosedEvent(String dialogueId, String vesselId, Deal.Outcome outcome) implements Event {
}
