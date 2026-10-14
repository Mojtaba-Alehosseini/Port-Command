package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;

/**
 * Pure model for the example a walk-in tab's input field shows while it is empty (S15,
 * 2026-10-12): a short rotation of things the player can type, every one of which the negotiation
 * grammar ({@code dcg_negotiation.pl}) parses in the state the tab is in. No Swing/AWT import; the
 * view paints {@link #placeholderFor} as a grey prompt and never puts it in the field's text.
 *
 * <p><b>The numbers are this tab's own.</b> A fixed example such as "1900 for 6h" is wrong on most
 * tabs and dangerous on some: the vessel accepts any demand at or below its standing offer, so
 * that sentence copied onto a tanker already offering €6000 closes the deal at €1900. Every
 * absolute price shown here is therefore computed ABOVE the vessel's standing offer
 * ({@link #priceExample}, {@link #makeItExample}), and the hours are the hours under discussion.
 * The relative examples ("200 more", "split the difference") are safe by construction.
 *
 * <p><b>No example gives anything away (S18b, UI tree U2).</b> None refuses the vessel — a reject
 * ends the negotiation for good — and since S18b none accepts it or concedes either. "deal" left
 * the rotation: copied, it takes the vessel's lowest price for good. "split the difference" left
 * too: it is the midpoint of the two offers, below the player's own demand by construction. And
 * no price shown is below the player's own last demand ({@link DialogueTabModel#playerHasOffered()}):
 * an example above the vessel's offer but under what the player asked teaches conceding.
 * {@link #ACCEPT_UTTERANCE} and {@link #REJECT_UTTERANCE} exist as what the buttons send: Accept,
 * Reject, and since S17a "Use this" on a Hint that recommends either.
 *
 * <p><b>It advances on a send, never on a timer.</b> The index is the number of messages the
 * player has sent in the tab ({@link DialogueTabModel#playerMessageCount()}): the prompt changes
 * exactly when the player has just used the field, and sits still while they read.
 *
 * <p>Reads ONLY {@link WalkInDialogueSnapshot}'s observable fields (P-04 —
 * {@code NegotiationTabRegistryTest}'s grep gate covers this file too).
 * {@code InputExamplesDcgTest} runs every example through the real grammar.
 */
public final class InputExamples {

    /** What the Accept button sends (never an example since S18b). The grammar reads it as an
     * accept in every context ({@code accept_phrase --> [deal]}). */
    public static final String ACCEPT_UTTERANCE = "deal";
    /** What the Reject button sends (and "Use this" for a say-no Hint, S17a). A button literal
     * ONLY — never an example (see the class comment). The grammar reads it as a reject in every context ({@code reject_phrase --> [no]}). */
    public static final String REJECT_UTTERANCE = "no";

    /** A relative counter: the player's last offer plus 200 — or, before they have offered, the
     * vessel's offer plus 200. Above the vessel's standing offer, and the player's demand, either way. */
    static final String DELTA = "200 more";
    /** What the view puts in front of the example. */
    static final String PREFIX = "e.g. ";

    /** Hours the grammar accepts in an offer ({@code duration_count}: 1..24). */
    static final int MIN_HOURS = 1;
    static final int MAX_HOURS = 24;

    private static final long STEP = 50;

    private InputExamples() {
    }

    /**
     * A full price the player could ask: 30% over the vessel's standing offer, rounded UP to the
     * next multiple of 50, and never less than 100 over it.
     *
     * @param theirOffer the vessel's standing offer in euros
     * @return whole euros, strictly above {@code theirOffer}
     */
    public static long priceExample(double theirOffer) {
        return Math.max(roundUpToStep(theirOffer * 1.30), wholeEurosAtLeast(theirOffer + 100));
    }

    /**
     * A second, lower price for the "make it …" example: 20% over the vessel's standing offer,
     * rounded UP to the next multiple of 50, and never less than 50 over it. When that lands on
     * {@link #priceExample} the two would teach the same number, so it steps 50 down — which is
     * still above the vessel's offer, because {@code priceExample} is at least 100 over it.
     *
     * @param theirOffer the vessel's standing offer in euros
     * @return whole euros, strictly above {@code theirOffer} and different from {@link #priceExample}
     */
    public static long makeItExample(double theirOffer) {
        long makeIt = Math.max(roundUpToStep(theirOffer * 1.20), wholeEurosAtLeast(theirOffer + 50));
        return makeIt == priceExample(theirOffer) ? makeIt - STEP : makeIt;
    }

    /** The hours an example names: the hours under discussion, kept inside what the grammar
     * accepts (an out-of-range count would make the whole sentence fail to parse). */
    public static int hoursExample(int durationHours) {
        return Math.max(MIN_HOURS, Math.min(MAX_HOURS, durationHours));
    }

    /**
     * The price-and-hours sentence for a vessel offering {@code theirOffer} for
     * {@code durationHours} — {@code 1900 for 6h}. Whole euros, no thousands separator.
     */
    public static String priceAndHoursExample(double theirOffer, int durationHours) {
        return priceExample(theirOffer) + " for " + hoursExample(durationHours) + "h";
    }

    /** S18b (U2): the lowest price an example may name — the player's own last demand, rounded
     * up to the next multiple of 50; nothing while they have not asked for anything. */
    private static long demandFloor(double playerDemand) {
        return playerDemand > 0 && !Double.isInfinite(playerDemand) ? roundUpToStep(playerDemand) : 0;
    }

    /**
     * The price-and-hours sentence for this tab, for text that wants ONE worked example (the
     * "I didn't get that" line). Empty when the tab has nothing to show ({@link #examplesFor}).
     */
    public static String priceAndHoursExample(DialogueTabModel tab) {
        List<String> examples = examplesFor(tab);
        return examples.isEmpty() ? "" : examples.get(0);
    }

    /**
     * The rotation for a vessel offering {@code theirOffer} for {@code durationHours}, to a player
     * whose last demand was {@code playerDemand} ({@code 0} when they have none): price-and-hours,
     * 200 more, make it … — every price at or above that demand (S18b, U2). When the demand
     * lifts both prices to the same number, "make it" would only repeat it and is left out.
     */
    public static List<String> examplesFor(double theirOffer, int durationHours, double playerDemand) {
        long floor = demandFloor(playerDemand);
        long price = Math.max(priceExample(theirOffer), floor);
        long makeIt = Math.max(makeItExample(theirOffer), floor);
        List<String> examples = new ArrayList<>(3);
        examples.add(price + " for " + hoursExample(durationHours) + "h");
        examples.add(DELTA);
        if (makeIt != price) {
            examples.add("make it " + makeIt);
        }
        return List.copyOf(examples);
    }

    /**
     * The rotation for this tab, in order. Empty for a closed tab, and for a tab with no vessel
     * offer on the table (no such tab is ever opened — a tab is born from the vessel's opening
     * offer — but with nothing to complete from, none of the price examples would parse).
     */
    public static List<String> examplesFor(DialogueTabModel tab) {
        Objects.requireNonNull(tab, "tab");
        if (tab.isClosed()) {
            return List.of();
        }
        WalkInDialogueSnapshot s = tab.snapshot();
        double theirOffer = s.lastVesselOffer();
        if (!(theirOffer > 0) || Double.isInfinite(theirOffer)) { // also false for NaN
            return List.of();
        }
        return examplesFor(theirOffer, s.durationHours(), tab.playerHasOffered() ? s.lastPlayerOffer() : 0);
    }

    /**
     * The example shown after the player has sent {@code playerSends} messages: the rotation's
     * entry at that index, wrapping round. Empty when the tab has no examples.
     */
    public static String exampleAt(DialogueTabModel tab, int playerSends) {
        List<String> examples = examplesFor(tab);
        return examples.isEmpty() ? "" : examples.get(Math.floorMod(playerSends, examples.size()));
    }

    /**
     * What the tab's empty input field shows right now: {@code e.g. 1900 for 6h}. The empty
     * string — no prompt at all — for a closed tab (its field is not editable).
     */
    public static String placeholderFor(DialogueTabModel tab) {
        Objects.requireNonNull(tab, "tab");
        String example = exampleAt(tab, tab.playerMessageCount());
        return example.isEmpty() ? "" : PREFIX + example;
    }

    /** {@code amount} rounded up to a multiple of 50. The tiny tolerance keeps a product that is
     * a whole multiple on paper (1500 &times; 1.30 = 1950) from being pushed a full step up by
     * binary floating point landing a hair above it. */
    private static long roundUpToStep(double amount) {
        return (long) Math.ceil(amount / STEP - 1e-9) * STEP;
    }

    /** The smallest whole-euro amount that is not below {@code amount} (same tolerance). */
    private static long wholeEurosAtLeast(double amount) {
        return (long) Math.ceil(amount - 1e-9);
    }
}
