package it.unige.portcommand.gui.model;

import java.util.Locale;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.ontology.Deal;

/**
 * S18b (playtest 2, PT3; UI tree U3): what the negotiation itself writes into a walk-in tab — the
 * vessel's opening offer, each of its replies, and the one line that ends it. Plain sentences
 * with the price and the hours in them:
 * <ul>
 *   <li>{@code They offer €1,460 for 5h at berth 2.}</li>
 *   <li>{@code They counter: €1,460 for 5h.}</li>
 *   <li>{@code They accept €1,600 but need at least 6h.}</li>
 *   <li>{@code ✔ They accepted €1,700 for 6h — deal done.}</li>
 *   <li>{@code ✖ They left (price too high).}</li>
 * </ul>
 * They replace "Opens at…", "Counters at…" and the figure-less "Deal closed." the playtest found
 * hard to read. No Swing/AWT import.
 *
 * <p>Reads ONLY {@link WalkInDialogueSnapshot}'s observable fields and the close's own terms
 * (P-04 — {@code NegotiationTabRegistryTest}'s grep gate covers this file too). The "they need at
 * least Nh" reply is derived from the two hours values on the wire, as it was before S18b: the
 * floor's value is public exactly when, and because, the vessel counters at it.
 */
public final class OutcomeLines {

    /** In front of a deal's line (U+2714 HEAVY CHECK MARK and a space). */
    static final String DEAL_MARK = "✔ ";
    /** In front of a line that ends a negotiation without a deal (U+2716 HEAVY MULTIPLICATION X). */
    static final String NO_DEAL_MARK = "✖ ";

    private OutcomeLines() {
    }

    /** The vessel's first offer: {@code They offer €1,460 for 5h at berth 2.} */
    public static String opening(WalkInDialogueSnapshot s) {
        String berth = s.berthId() == null || s.berthId().isBlank() ? ""
                : " at " + PortTableModel.berthLabel(s.berthId()).toLowerCase(Locale.ROOT);
        return "They offer " + Euros.of(s.lastVesselOffer()) + " for " + s.durationHours() + "h" + berth + ".";
    }

    /**
     * A later reply of the vessel's.
     *
     * @param playerHours the hours the player's last offer carried; {@code null} before they named any
     * @return {@code They accept €1,600 but need at least 6h.} when the vessel took the player's
     *         price and pushed only the hours back; {@code They counter: €1,460 for 6h — they need
     *         at least 6h.} when it pushed both; {@code They counter: €1,460 for 5h.} otherwise
     */
    public static String counter(WalkInDialogueSnapshot s, Integer playerHours) {
        boolean moreHours = playerHours != null && s.durationHours() > playerHours;
        if (moreHours && s.lastPlayerOffer() > 0
                && Math.round(s.lastVesselOffer()) == Math.round(s.lastPlayerOffer())) {
            return "They accept " + Euros.of(s.lastVesselOffer()) + " but need at least " + s.durationHours() + "h.";
        }
        String line = "They counter: " + Euros.of(s.lastVesselOffer()) + " for " + s.durationHours() + "h";
        return moreHours ? line + " — they need at least " + s.durationHours() + "h." : line + ".";
    }

    /**
     * The line that ends the negotiation.
     *
     * @param price          the agreed price of a deal; 0 when the close named none
     * @param hours          the agreed stay of a deal
     * @param vesselAccepted whether the vessel accepted the player's own price (otherwise the deal
     *                       is the vessel's offer, accepted on the player's side — by the player or
     *                       by the Assistant for them, so the line names nobody)
     */
    public static String closing(Deal.Outcome outcome, double price, int hours, boolean vesselAccepted) {
        return switch (outcome) {
            case DEAL -> {
                if (!(price > 0)) {
                    yield DEAL_MARK + "Deal done.";
                }
                String terms = Euros.of(price) + " for " + hours + "h";
                yield vesselAccepted
                        ? DEAL_MARK + "They accepted " + terms + " — deal done."
                        : DEAL_MARK + "Deal done: " + terms + ".";
            }
            case WITHDRAW_PRICE -> NO_DEAL_MARK + "They left (price too high).";
            case WITHDRAW_DURATION -> NO_DEAL_MARK + "They left (you never granted the hours they need).";
            case TIMEOUT -> NO_DEAL_MARK + "They left (negotiation timed out).";
            case PLAYER_REFUSED -> NO_DEAL_MARK + "You turned them away.";
        };
    }
}
