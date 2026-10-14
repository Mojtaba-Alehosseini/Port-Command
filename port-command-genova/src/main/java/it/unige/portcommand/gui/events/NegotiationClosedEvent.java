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
 * <p>S18b (playtest 2, PT3; UI tree U3): a {@code DEAL} close carries the agreed terms, so the
 * tab can say what was agreed ("✔ They accepted €1,700 for 6h — deal done.") and keep the
 * figures on its offer line and in the Finished list. Before S18b it carried none, the tab said
 * "Deal closed." and the offer line "Negotiation closed.".
 *
 * @param dialogueId     the negotiation's conversation id
 * @param vesselId       the vessel that was negotiating
 * @param outcome        DEAL / WITHDRAW_PRICE / WITHDRAW_DURATION / TIMEOUT / PLAYER_REFUSED
 * @param price          the agreed price of a {@code DEAL}; 0 for every other outcome
 * @param hours          the agreed stay of a {@code DEAL}; 0 for every other outcome
 * @param vesselAccepted true when the vessel accepted the player's own last price (the
 *                       HarbourMaster's record of it — the player's or the Assistant's PROPOSE);
 *                       false when the deal is the vessel's own offer, accepted on the player's
 *                       side, and for every outcome but {@code DEAL}
 */
public record NegotiationClosedEvent(String dialogueId, String vesselId, Deal.Outcome outcome,
                                     double price, int hours, boolean vesselAccepted) implements Event {

    /** A close without terms: every outcome but {@code DEAL}. */
    public NegotiationClosedEvent(String dialogueId, String vesselId, Deal.Outcome outcome) {
        this(dialogueId, vesselId, outcome, 0.0, 0, false);
    }
}
