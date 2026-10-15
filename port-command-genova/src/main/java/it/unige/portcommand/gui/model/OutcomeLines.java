package it.unige.portcommand.gui.model;

import java.util.Locale;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.ontology.Deal;

/**
 * S18b (playtest 2, PT3; UI tree U3): what the negotiation itself writes into a walk-in tab — the
 * vessel's opening offer, each of its replies, and the one line that ends it. Plain sentences
 * with the price and the hours in them:
 * <ul>
 *   <li>{@code Faro Vecchio offers €1,460 for 5h at berth 2.}</li>
 *   <li>{@code Faro Vecchio counters: €1,460 for 5h.}</li>
 *   <li>{@code Faro Vecchio accepts €1,600 but needs at least 6h.}</li>
 *   <li>{@code ✔ Faro Vecchio accepted €1,700 for 6h — deal done.}</li>
 *   <li>{@code ✖ Faro Vecchio left (price too high).}</li>
 * </ul>
 * S19 (playtest 5, PT32): every line names the ship - "They counter…" left the player asking who
 * "they" were, and whether there was a middleman. Without a name (none is ever missing in play) the
 * lines say "They", as before.
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

    /** The vessel's first offer: {@code Faro Vecchio offers €1,460 for 5h at berth 2.} */
    public static String opening(WalkInDialogueSnapshot s) {
        String berth = s.berthId() == null || s.berthId().isBlank() ? ""
                : " at " + PortTableModel.berthLabel(s.berthId()).toLowerCase(Locale.ROOT);
        String name = nameOf(s.vesselName());
        return (name == null ? "They offer " : name + " offers ") + Euros.of(s.lastVesselOffer()) + " for "
                + s.durationHours() + "h" + berth + ".";
    }

    /**
     * A later reply of the vessel's.
     *
     * @param playerHours the hours the player's last offer carried; {@code null} before they named any
     * @return {@code Faro Vecchio accepts €1,600 but needs at least 6h.} when the vessel took the
     *         player's price and pushed only the hours back; {@code Faro Vecchio counters: €1,460 for
     *         6h — they need at least 6h.} when it pushed both; {@code Faro Vecchio counters: €1,460
     *         for 5h.} otherwise
     */
    public static String counter(WalkInDialogueSnapshot s, Integer playerHours) {
        boolean moreHours = playerHours != null && s.durationHours() > playerHours;
        String name = nameOf(s.vesselName());
        if (moreHours && s.lastPlayerOffer() > 0
                && Math.round(s.lastVesselOffer()) == Math.round(s.lastPlayerOffer())) {
            return (name == null ? "They accept " : name + " accepts ") + Euros.of(s.lastVesselOffer())
                    + (name == null ? " but need at least " : " but needs at least ") + s.durationHours() + "h.";
        }
        String line = (name == null ? "They counter: " : name + " counters: ") + Euros.of(s.lastVesselOffer())
                + " for " + s.durationHours() + "h";
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
    public static String closing(String vesselName, Deal.Outcome outcome, double price, int hours,
                                 boolean vesselAccepted) {
        String name = nameOf(vesselName);
        String who = name == null ? "They" : name;
        return switch (outcome) {
            case DEAL -> {
                if (!(price > 0)) {
                    yield DEAL_MARK + "Deal done.";
                }
                String terms = Euros.of(price) + " for " + hours + "h";
                yield vesselAccepted
                        ? DEAL_MARK + who + " accepted " + terms + " — deal done."
                        : DEAL_MARK + "Deal done: " + terms + ".";
            }
            case WITHDRAW_PRICE -> NO_DEAL_MARK + who + " left (price too high).";
            case WITHDRAW_DURATION -> NO_DEAL_MARK + who + " left (you never granted the hours they need).";
            case TIMEOUT -> NO_DEAL_MARK + who + " left (negotiation timed out).";
            case PLAYER_REFUSED -> NO_DEAL_MARK + "You turned " + (name == null ? "them" : name) + " away.";
        };
    }

    /** As {@link #closing(String, Deal.Outcome, double, int, boolean)} with no name known. */
    public static String closing(Deal.Outcome outcome, double price, int hours, boolean vesselAccepted) {
        return closing(null, outcome, price, hours, vesselAccepted);
    }

    /** S19 (PT32): the ship's name, or {@code null} when it has none to say. */
    private static String nameOf(String vesselName) {
        return vesselName == null || vesselName.isBlank() ? null : vesselName.trim();
    }
}
