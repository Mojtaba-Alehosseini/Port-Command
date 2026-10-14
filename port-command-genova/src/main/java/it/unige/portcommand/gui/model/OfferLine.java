package it.unige.portcommand.gui.model;

import java.util.Objects;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.ontology.Deal;

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
 * <p><b>S18b: a closed tab keeps its figures; the last round says so.</b> A finished negotiation
 * used to read just "Negotiation closed." (UI tree U3). It now shows what was agreed —
 * {@code Deal: €1,700 · 6h} — or, without a deal, the two last offers. And the round
 * the vessel's patience ends with reads {@code final round (6 of 6)} instead of a plain
 * {@code round 6 of 6} (U9): the HarbourMaster's own {@code finalRound} rule, from the same two
 * public numbers. Amounts are written as everywhere in the chat since S18b ({@link Euros}).
 *
 * <p>Reads ONLY {@link WalkInDialogueSnapshot}'s observable fields (P-04 —
 * {@code NegotiationTabRegistryTest}'s grep gate covers this file too). It says nothing about the
 * time the vessel will still wait: the {@code m:ss} counter beside it is a bare number and stays
 * unexplained.
 *
 * @param theirs row 1 — {@code Their offer €1,436 · 6h}; for a closed tab the outcome
 *               and its figures ({@code Deal: €1,700 · 6h},
 *               {@code No deal — their last offer €1,436 · 6h})
 * @param yours  the player's half of row 2 — {@code Your offer €1,900 · 4h}, or
 *               {@code Your offer —} when none stands; for a closed tab without a deal, the
 *               player's last offer ({@code Your last offer €1,900 · 4h}) or empty
 * @param round  the round's half of row 2 — {@code round 2 of 6}, {@code final round (6 of 6)};
 *               empty for a closed tab
 */
public record OfferLine(String theirs, String yours, String round) {

    /** Row 1 of a deal's closed tab, before its figures. */
    static final String DEAL_PREFIX = "Deal: ";
    /** Row 1 of a closed tab whose close named no figures (a deal reported without terms). */
    static final String DEAL_DONE = "Deal done.";
    /** Row 1 of a closed tab that ended without a deal, before the vessel's last offer. */
    static final String NO_DEAL_PREFIX = "No deal \u2014 their last offer ";

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
            return closedLine(tab);
        }
        WalkInDialogueSnapshot s = tab.snapshot();
        String theirs = "Their offer " + Euros.of(s.lastVesselOffer()) + DOT + s.durationHours() + "h";
        String yours = tab.playerHasOffered() ? "Your offer " + playersTerms(tab) : "Your offer " + NO_OFFER;
        // S18b (U9): the HarbourMaster's own finalRound rule (roundLimit + 1 - roundsUsed <= 1).
        String round = s.roundsUsed() >= roundLimit
                ? "final round (" + s.roundsUsed() + " of " + roundLimit + ")"
                : "round " + s.roundsUsed() + " of " + roundLimit;
        return new OfferLine(theirs, yours, round);
    }

    /** S18b (U3): a finished negotiation keeps its figures — the deal's, or the two last offers. */
    private static OfferLine closedLine(DialogueTabModel tab) {
        if (tab.outcome() == Deal.Outcome.DEAL) {
            return tab.agreedPrice() > 0
                    ? new OfferLine(DEAL_PREFIX + Euros.of(tab.agreedPrice()) + DOT + tab.agreedHours() + "h", "", "")
                    : new OfferLine(DEAL_DONE, "", "");
        }
        WalkInDialogueSnapshot s = tab.snapshot();
        String theirs = NO_DEAL_PREFIX + Euros.of(s.lastVesselOffer()) + DOT + s.durationHours() + "h";
        String yours = tab.playerHasOffered() ? "Your last offer " + playersTerms(tab) : "";
        return new OfferLine(theirs, yours, "");
    }

    /** {@code €1,900 · 4h}: the player's standing price, with the hours their own last
     * PROPOSE carried — or, when the price on the strip was stamped by the HarbourMaster alone
     * (an autopilot counter, a reload), the hours under discussion, as before S15. */
    private static String playersTerms(DialogueTabModel tab) {
        WalkInDialogueSnapshot s = tab.snapshot();
        int playerHours = tab.lastPlayerHours() != null ? tab.lastPlayerHours() : s.durationHours();
        return Euros.of(s.lastPlayerOffer()) + DOT + playerHours + "h";
    }

    /** True for a finished negotiation: there is no round any more. */
    public boolean closed() {
        return round.isEmpty();
    }

    /** Row 2 — {@code Your offer €1,900 · 4h · round 2 of 6}; for a closed tab the
     * player's last offer alone (empty when there was none, or the negotiation ended in a deal). */
    public String yoursAndRound() {
        return closed() ? yours : yours + DOT + round;
    }

    /** The whole line: row 1, {@code " / "}, row 2 — row 1 alone when there is no row 2. */
    public String text() {
        String second = yoursAndRound();
        return second.isEmpty() ? theirs : theirs + ROW_BREAK + second;
    }
}
