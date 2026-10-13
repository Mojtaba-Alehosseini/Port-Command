package it.unige.portcommand.assistant;

import java.util.Objects;

/**
 * What one Hint recommends, in the form the player can act on (S17a, ADR-21, decision A3).
 *
 * <p>A Hint used to be a paragraph of text and nothing else. The chat tab now shows a card: the
 * recommendation in a few plain words, a details line with the numbers and the checks behind it,
 * and a "Use this" button that sends exactly what the card shows. For that the tab needs the
 * recommendation as DATA — and it needs to know which offer the recommendation answered, because
 * a vessel can counter while the card is on screen. {@code round} and {@code vesselOffer} are that
 * memory: {@link #describes} is false as soon as either changed, and the button then reads
 * "Outdated — press Hint again" instead of sending advice about an offer that is gone (accepting
 * "their offer" after it moved would take a price the Assistant never looked at).
 *
 * <p>Everything here is public to the player: the action and price are the recommendation
 * itself, and the round, the hours and the vessel's offer are on the tab's own offer line. No
 * hidden belief is carried (P-04).
 *
 * @param action      {@link RecommendationCandidate#ACCEPT}, {@link RecommendationCandidate#COUNTER}
 *                    or {@link RecommendationCandidate#REJECT_SILENT} ("say no")
 * @param price       the recommended price, as the algorithm computed it (0.0 for "say no")
 * @param hours       the stay under discussion when the Hint was asked for
 * @param round       the dialogue's round when the Hint was asked for
 * @param vesselOffer the vessel's standing offer the recommendation answered
 * @param suggestion  the recommendation in a few words, e.g. {@code counter at €1580 for 6h}
 * @param details     the details line, e.g. {@code EV €640 · chance 62% · R7 ✓}
 */
public record HintCard(String action, double price, int hours, int round, double vesselOffer,
                       String suggestion, String details) {

    public HintCard {
        Objects.requireNonNull(action, "action");
        suggestion = suggestion == null ? "" : suggestion;
        details = details == null ? "" : details;
    }

    /** The card for {@code rec}, which was computed from {@code snapshot}. */
    public static HintCard of(Recommendation rec, WalkInDialogueSnapshot snapshot, String details) {
        return new HintCard(rec.action(), rec.price(), snapshot.durationHours(), snapshot.roundsUsed(),
                snapshot.lastVesselOffer(), AssistantWording.suggestion(rec, snapshot), details);
    }

    /** The price as every text shows it: whole euros. */
    public long shownPrice() {
        return Math.round(price);
    }

    public boolean isAccept() {
        return RecommendationCandidate.ACCEPT.equals(action);
    }

    public boolean isCounter() {
        return RecommendationCandidate.COUNTER.equals(action);
    }

    /** "Say no": the algorithm's {@code reject_silent}, which since S17a is an explicit no. */
    public boolean isSayNo() {
        return RecommendationCandidate.REJECT_SILENT.equals(action);
    }

    /**
     * Whether this card still answers the offer on the table: the same round, the same vessel
     * offer. Compared exactly — both values come from the same snapshots, never from arithmetic.
     */
    public boolean describes(WalkInDialogueSnapshot now) {
        return now != null && now.roundsUsed() == round
                && Double.compare(now.lastVesselOffer(), vesselOffer) == 0;
    }
}
