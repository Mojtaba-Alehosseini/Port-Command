package it.unige.portcommand.gui.model;

import java.util.Locale;
import java.util.Objects;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;

/**
 * Pure rendering model for the line above a walk-in tab's transcript (S15, 2026-10-12): what the
 * vessel is offering, what the player last offered, and which round this is. No Swing/AWT import —
 * {@code DialogueTabView} only puts the two rows into two labels.
 *
 * <p><b>Two rows, one sentence.</b> Written on one line it reads
 * {@code Their offer €1436 · 6h / Your offer €1900 · 4h · round 2 of 6}; that is too wide to sit
 * beside the counter in the chat column at its minimum width, and the part an ellipsis would eat
 * is the round. So the view breaks it at the {@code " / "}: {@link #theirs()} on the first row,
 * {@link #yoursAndRound()} on the second. {@link #text()} is the whole sentence (the tooltip, and
 * what tests read).
 *
 * <p><b>"Your offer —".</b> Before the player has offered, the snapshot's player price is the
 * {@code 0.0} sentinel. The pre-S15 strip printed it ("Your offer: €0 for 6h"), which reads as an
 * offer of nothing; this line says there is none. The same sentinel comes back when the Prolog
 * gate refuses the player's first offer, so the line goes back to the dash with it.
 *
 * <p>Reads ONLY {@link WalkInDialogueSnapshot}'s observable fields (P-04 —
 * {@code NegotiationTabRegistryTest}'s grep gate covers this file too). It says nothing about the
 * time the vessel will still wait: the {@code m:ss} counter beside it is a bare number and stays
 * unexplained.
 *
 * @param theirs row 1 — {@code Their offer €1436 · 6h}; for a closed tab, {@link #CLOSED_TEXT}
 * @param yours  the player's half of row 2 — {@code Your offer €1900 · 4h}, or
 *               {@code Your offer —} when none stands; empty for a closed tab
 * @param round  the round's half of row 2 — {@code round 2 of 6}; empty for a closed tab
 */
public record OfferLine(String theirs, String yours, String round) {

    /** The whole line once the negotiation is over (unchanged from the pre-S15 strip). */
    public static final String CLOSED_TEXT = "Negotiation closed.";

    /** Between the terms inside one side's offer: space, U+00B7 MIDDLE DOT, space. */
    static final String DOT = " · ";
    /** Where the one-line form breaks into the two rows. */
    static final String ROW_BREAK = " / ";
    /** Stands where the player's price would be while they have no offer standing (U+2014). */
    static final String NO_OFFER = "—";

    public OfferLine {
        Objects.requireNonNull(theirs, "theirs");
        Objects.requireNonNull(yours, "yours");
        Objects.requireNonNull(round, "round");
    }

    /**
     * @param tab        the tab to describe
     * @param roundLimit the negotiation's round limit ({@code Settings.load().roundLimit()} in the
     *                   view; a parameter here so this stays a pure function of its inputs)
     */
    public static OfferLine of(DialogueTabModel tab, int roundLimit) {
        Objects.requireNonNull(tab, "tab");
        if (tab.isClosed()) {
            return new OfferLine(CLOSED_TEXT, "", "");
        }
        WalkInDialogueSnapshot s = tab.snapshot();
        String theirs = "Their offer " + euros(s.lastVesselOffer()) + DOT + s.durationHours() + "h";
        String yours;
        if (tab.playerHasOffered()) {
            // The hours the player's own last PROPOSE carried; null when the price on the strip
            // was stamped by the HarbourMaster alone (an autopilot counter, a reload) — then, as
            // before S15, the hours under discussion are what the vessel heard it at.
            int playerHours = tab.lastPlayerHours() != null ? tab.lastPlayerHours() : s.durationHours();
            yours = "Your offer " + euros(s.lastPlayerOffer()) + DOT + playerHours + "h";
        } else {
            yours = "Your offer " + NO_OFFER;
        }
        return new OfferLine(theirs, yours, "round " + s.roundsUsed() + " of " + roundLimit);
    }

    /** True for a finished negotiation: the line is {@link #CLOSED_TEXT} and there is no row 2. */
    public boolean closed() {
        return round.isEmpty();
    }

    /** Row 2 — {@code Your offer €1900 · 4h · round 2 of 6}; empty for a closed tab. */
    public String yoursAndRound() {
        return closed() ? "" : yours + DOT + round;
    }

    /** The whole line: row 1, {@code " / "}, row 2 — or just {@link #CLOSED_TEXT}. */
    public String text() {
        return closed() ? theirs : theirs + ROW_BREAK + yoursAndRound();
    }

    /** Whole euros, as everywhere else on the strip. {@link Locale#ROOT} so the digits are the
     * same on every machine (a default-locale format may print non-ASCII digits). */
    private static String euros(double amount) {
        return "€" + String.format(Locale.ROOT, "%.0f", amount);
    }
}
