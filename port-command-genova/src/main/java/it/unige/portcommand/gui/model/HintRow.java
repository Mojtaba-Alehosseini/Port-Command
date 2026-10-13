package it.unige.portcommand.gui.model;

import java.util.Objects;
import java.util.Optional;

import it.unige.portcommand.assistant.HintCard;

/**
 * Pure model for the row a walk-in tab shows above its input once a Hint has been given (S17a,
 * ADR-21, decision A3, playtest item PT13): {@code Assistant suggests: counter at €1580 for 6h}
 * and one button. No Swing/AWT import — {@code DialogueTabView} puts {@link #suggestion()} into a
 * label and turns {@link #state()} into the button's text and whether it can be pressed.
 *
 * <p><b>"Use this" types for the player.</b> Like Accept and Reject (S15), the button sends what
 * the player could have typed, down the same path as typed text: {@code deal} for a
 * recommendation to accept, {@code no} for one to say no, and for a counter the price and hours
 * in the form the input's own first example uses ({@code 1580 for 6h} — the form
 * {@code InputExamplesDcgTest} runs through the real grammar). The price is the card's
 * {@link HintCard#shownPrice()}: the whole euros every text about this recommendation shows, so
 * what is sent is what the row says, digit for digit.
 *
 * <p><b>A card can go stale.</b> The vessel may counter while the card is on screen; "accept
 * their offer" would then accept a price the Assistant never looked at. The card remembers the
 * round and the vessel's offer it answered ({@link HintCard#describes}); once either has moved
 * the row is {@link State#STALE} — the button says so and sends nothing, and {@link #utterance()}
 * is empty so nothing can be sent from it by mistake either. A new Hint brings a new card.
 *
 * <p><b>When there is no row.</b> No Hint has brought a card; the card was used (one card, one
 * send); the negotiation is closed; or the Assistant — not the player — is negotiating in the tab,
 * in which case the tab shows its "Assistant is negotiating…" marker instead and offers the
 * player nothing to send.
 *
 * <p>Reads ONLY the tab's observable snapshot and the card, which carries nothing the player
 * cannot see (P-04 — {@code NegotiationTabRegistryTest}'s grep gate covers this file too). Says
 * nothing about time.
 *
 * @param state      whether there is a row, and if so whether its button can send
 * @param suggestion the label's text — {@code Assistant suggests: …}; empty when hidden
 * @param utterance  what "Use this" sends; empty unless {@link State#FRESH}
 */
public record HintRow(State state, String suggestion, String utterance) {

    /** What the row is showing. */
    public enum State {
        /** No row at all. */
        HIDDEN,
        /** The card answers the offer on the table: the button sends {@link HintRow#utterance()}. */
        FRESH,
        /** The vessel has countered since the Hint: the button says so and cannot be pressed. */
        STALE
    }

    /** What stands in front of the card's own few words in the label. */
    public static final String SUGGESTS_PREFIX = "Assistant suggests: ";

    private static final HintRow NONE = new HintRow(State.HIDDEN, "", "");

    public HintRow {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(suggestion, "suggestion");
        Objects.requireNonNull(utterance, "utterance");
    }

    /**
     * The row for {@code tab} as it stands right now.
     *
     * @param tab           the tab to describe; {@code null} (no such tab any more) gives no row
     * @param assistantOwns whether the Assistant is negotiating in this tab
     *                      ({@code AutopilotOwnershipEvent.assistantOwns}) — a parameter so this
     *                      stays a pure function of its inputs
     */
    public static HintRow of(DialogueTabModel tab, boolean assistantOwns) {
        if (tab == null || tab.isClosed() || assistantOwns) {
            return NONE;
        }
        Optional<HintCard> unused = tab.hintCard();
        if (unused.isEmpty()) {
            return NONE;
        }
        HintCard card = unused.get();
        String utterance = utteranceFor(card);
        if (utterance.isEmpty()) {
            return NONE; // a recommendation the tab has no words for is not offered as a button
        }
        String suggestion = SUGGESTS_PREFIX + card.suggestion();
        return card.describes(tab.snapshot())
                ? new HintRow(State.FRESH, suggestion, utterance)
                : new HintRow(State.STALE, suggestion, "");
    }

    /**
     * What a player would type to do what {@code card} recommends: {@code deal}, {@code no}, or
     * {@code 1580 for 6h}. Empty for an action that is none of the three.
     */
    public static String utteranceFor(HintCard card) {
        Objects.requireNonNull(card, "card");
        if (card.isAccept()) {
            return InputExamples.ACCEPT_UTTERANCE;
        }
        if (card.isSayNo()) {
            return InputExamples.REJECT_UTTERANCE;
        }
        if (card.isCounter()) {
            return card.shownPrice() + " for " + card.hours() + "h";
        }
        return "";
    }

    /** Whether the tab shows the row at all. */
    public boolean visible() {
        return state != State.HIDDEN;
    }

    /** Whether the row's button can send: the card still answers the offer on the table. */
    public boolean fresh() {
        return state == State.FRESH;
    }
}
