package it.unige.portcommand.nlp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the chat refuses to send, and what it says instead (S18a, ADR-23).
 *
 * <p>A vessel accepts any demand at or below its own offer, so a typed line that the pipeline
 * reads as a low price — or as an acceptance — is a binding money action the moment it is routed.
 * Before S18a a bare "8" was a demand of EUR 8, "yes 2000 works" closed at the vessel's price and
 * "cancel" sent the vessel away. The checks here sit between the parse and the wire: each one
 * recognises a line that most likely does NOT mean what it parses to, and answers it with a
 * sentence that says what was read and what to type instead. Nothing here changes a negotiation:
 * a line that passes is routed exactly as before, and a line that does not is simply not sent.
 *
 * <p>Pure functions plus the sentences themselves, so the rules are testable without a pipeline
 * and the chat ({@code gui/DialogueTabView}) says the same words for the cases it answers itself.
 */
public final class InputSafety {

    /**
     * Where a message wants a worked example. The pipeline cannot write one: an example has to sit
     * ABOVE the vessel's offer, or copying it closes the deal cheaply, and the numbers that make
     * it so belong to that vessel's tab ({@code gui.model.InputExamples}). The chat fills the slot
     * ({@link #fillExample}) with the example of the tab the refused move was FOR; with none to
     * give, with {@link #GENERIC_EXAMPLE}.
     */
    public static final String EXAMPLE_SLOT = "{example}";

    /** What the slot reads as when there is no tab to take an example from. */
    public static final String GENERIC_EXAMPLE = "PRICE for Nh";

    /**
     * Hours run 1..24, berths 1..4 and tugs are counted on one hand: a number up to this is never a
     * price (decision N1). The grammar's own hour bound ({@code duration_count}, 1..24) is the
     * same figure on purpose — whatever could be hours is not sent as money.
     */
    static final int LARGEST_NON_PRICE = 24;

    /** A demand below this share of the vessel's own offer is a slip, not a strategy (N1). */
    static final double SMALLEST_SHARE_OF_THEIR_OFFER = 0.5;

    /** Decision N5: a policy typed into a chat tab. Also what the chat says for a policy only
     * Rasa recognised ({@code set_policy}). */
    public static final String POLICY_IN_CHAT =
            "Policies are set in Settings → Autopilot policy — nothing was sent to the vessel.";

    /** Decision N3: "cancel" / "never mind" in a negotiation with an offer on the table. */
    public static final String CANCEL_IN_A_NEGOTIATION =
            "That would end the negotiation — press Reject to confirm.";

    /**
     * S19 (playtest 5, PT27): a Rasa refusal below the binding floor. Rasa read a typed "hint" as
     * {@code reject_deal} at 0.86 and the vessel left with its offer; a departure cannot be undone,
     * so the Reject button - one click - is the way to it.
     */
    public static final String REJECT_NEEDS_THE_BUTTON = "Did you mean to turn them away? Press Reject.";

    /** Decision N10: Rasa saw an offer, the grammar could not read its terms. */
    public static final String UNREADABLE_OFFER =
            "I see an offer but couldn't read the terms — write it like '" + EXAMPLE_SLOT + "'.";

    /** A berth number is one or two digits; three or more after the word "berth" is a price. */
    private static final Pattern BERTH_NUMBER = Pattern.compile("\\d{1,2}");
    private static final Pattern DIGITS = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^a-z]+");
    private static final Set<String> HOUR_UNITS = Set.of("h", "hour", "hours", "hrs", "hr");

    /**
     * Words that are a number when they stand beside an acceptance or a refusal ("ok two
     * thousand"). They are only DETECTED, never parsed (number words are not read as prices —
     * decision N9). Words that are a number only sometimes are deliberately absent: "go with
     * that one" and "that's grand" are acceptances, and asking about them would cost a correct
     * route to guard against a reading nobody meant.
     */
    private static final Set<String> NUMBER_WORDS = Set.of(
            "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "fifteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
            "hundred", "thousand");

    private InputSafety() {
    }

    /** {@link #implausiblePrice(Frame, Double, String)} for a move typed in the vessel's own tab. */
    static Optional<String> implausiblePrice(Frame frame, Double theirOffer) {
        return implausiblePrice(frame, theirOffer, "their");
    }

    /**
     * Decision N1. A {@code propose}/{@code counter} frame whose amount cannot be meant as a price:
     * at most {@value #LARGEST_NON_PRICE} (hours, a berth, a typo), or below half of the offer the
     * vessel has on the table. Any other frame, and any other amount, is left alone.
     *
     * @param theirOffer the price the addressed vessel is offering, or {@code null} when it is not
     *                   known — then only the absolute rule applies. It must be the offer of the
     *                   vessel the move is FOR: another vessel's price says nothing about this one.
     * @param whose      how the sentence names that vessel's offer: {@code "their"} in its own
     *                   tab, {@code "Carthago's"} for a move addressed there from another tab
     * @return the sentence to show instead of sending, or empty when the price is plausible
     */
    static Optional<String> implausiblePrice(Frame frame, Double theirOffer, String whose) {
        Double amount = demandedAmount(frame);
        if (amount == null) {
            return Optional.empty();
        }
        if (amount <= LARGEST_NON_PRICE) {
            // The hint says hours go BESIDE a price: hours alone, on a tab with no bid yet, would
            // be completed with the vessel's own price (see closesAtTheirPrice).
            String whatToWrite = amount >= 1 && amount == Math.rint(amount)
                    ? "hours are written with an h, beside a price: '" + EXAMPLE_SLOT + "'."
                    : "a price looks like '" + EXAMPLE_SLOT + "'.";
            return Optional.of("Did you mean " + euros(amount) + "? A number that small is never sent "
                    + "as a price — " + whatToWrite);
        }
        if (usable(theirOffer) && amount < theirOffer * SMALLEST_SHARE_OF_THEIR_OFFER) {
            return Optional.of("Did you mean " + euros(amount) + "? That is less than half of " + whose
                    + " offer (" + euros(theirOffer) + "), so nothing was sent — a price looks like '"
                    + EXAMPLE_SLOT + "'.");
        }
        return Optional.empty();
    }

    /**
     * Hours without a price, on a tab where the player has not bid (review of S18a). The grammar
     * completes such a move from the reference offer, and with no bid of the player's that is the
     * VESSEL's offer: "8h" becomes a demand of exactly what the vessel offers, which it accepts at
     * once. A line about hours would close the deal at the opening price — an acceptance nobody
     * typed. It is recognised by its result: the demanded amount is the vessel's own price, the
     * player has no offer of their own, and the number is not in what was typed (a player who
     * TYPES the vessel's price means it, and is not stopped).
     *
     * @param playerHasOffered whether the player has a priced offer on the table
     * @param tokens           the tokeniser's reading of the typed line
     */
    static Optional<String> closesAtTheirPrice(Frame frame, Double theirOffer, boolean playerHasOffered,
                                               List<String> tokens, String whose) {
        Double amount = demandedAmount(frame);
        if (amount == null || playerHasOffered || !usable(theirOffer)
                || Math.abs(amount - theirOffer) > 0.005) {
            return Optional.empty();
        }
        for (String token : tokens) {
            try {
                if (Math.abs(Double.parseDouble(token) - amount) <= 0.005) {
                    return Optional.empty(); // the price was typed
                }
            } catch (NumberFormatException notANumber) {
                // a word
            }
        }
        String hours = frame.element("duration") instanceof Number d ? " for " + d.intValue() + "h" : "";
        return Optional.of("That would close at " + whose + " own price (" + euros(theirOffer) + ")" + hours
                + " — you gave hours but no price. Write both, like '" + EXAMPLE_SLOT
                + "', or press Accept to take " + whose + " offer as it stands.");
    }

    /**
     * Playtest decision PT7. The grammar refuses a berth that does not exist (only 1..4 do), and
     * the sentence then used to get the generic "I didn't get that". This finds the number the
     * player named, in the tokeniser's own output — so {@code berth 5}, {@code berth-5} and
     * {@code berth5} are one case — and answers by number.
     *
     * <p>A number after the word is a berth number only if it could be one: one or two digits,
     * and not followed by an hour unit. "same berth 2200" names a price and "free berth 5 hours"
     * a stay; answering either with "there is no berth 2200" would be false (review of S18a).
     */
    static Optional<String> noSuchBerth(List<String> tokens) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            String next = tokens.get(i + 1);
            if (!"berth".equals(tokens.get(i)) || !BERTH_NUMBER.matcher(next).matches()) {
                continue;
            }
            if (i + 2 < tokens.size() && HOUR_UNITS.contains(tokens.get(i + 2))) {
                continue;
            }
            int number = Integer.parseInt(next);
            if (number < 1 || number > 4) {
                return Optional.of("There is no berth " + number
                        + " — berths are 1 to 4 (ferries fit only berth 4).");
            }
        }
        return Optional.empty();
    }

    /** {@link #termsBesideADecision(boolean, PreprocessRegex.Extracted, String, Collection)} with
     * only the pre-pass to ask. */
    static Optional<String> termsBesideADecision(boolean accepting, PreprocessRegex.Extracted extracted) {
        return termsBesideADecision(accepting, extracted, "", List.of());
    }

    /**
     * Decision N4. Rasa classifies a whole sentence as accept or reject and ignores what else is
     * in it: "yes 2000 works" bound an ACCEPT of the vessel's price. If the sentence also names a
     * price, hours or a berth, it is not a bare acceptance or refusal, and it is asked about.
     *
     * <p>Three sources, in this order, because none sees everything (review of S18a): the regex
     * pre-pass, which reads the term properly but needs three digits for a bare price and a unit
     * for hours; then, only if it found nothing, a term Rasa itself extracted, any run of digits
     * in the sentence ("ok 8"), or a number word ("ok two thousand").
     *
     * @param accepting {@code true} for {@code accept_deal}, {@code false} for {@code reject_deal}
     * @param text      the typed line
     * @param rasaTerms the values of Rasa's price, time and berth entities for this line
     * @return the sentence to show instead of sending, or empty when no terms were named
     */
    static Optional<String> termsBesideADecision(boolean accepting, PreprocessRegex.Extracted extracted,
                                                 String text, Collection<String> rasaTerms) {
        List<String> named = new ArrayList<>();
        extracted.price().filter(p -> !p.isNaN() && !p.isInfinite()).ifPresent(p -> named.add(euros(p)));
        extracted.duration().ifPresent(d -> named.add(d + "h"));
        extracted.berthId().ifPresent(b -> named.add(b.replace('_', ' ')));
        if (named.isEmpty()) {
            otherNumber(text, rasaTerms).ifPresent(named::add);
        }
        if (named.isEmpty()) {
            return Optional.empty();
        }
        String terms = named.size() == 1 ? named.get(0)
                : String.join(", ", named.subList(0, named.size() - 1)) + " and " + named.get(named.size() - 1);
        return Optional.of(accepting
                ? "That reads as accepting their offer, but you also wrote " + terms + " — accepting takes "
                        + "their terms as they stand, so nothing was sent. For other terms write '"
                        + EXAMPLE_SLOT + "'; to take theirs press Accept."
                : "That reads as turning them away, but you also wrote " + terms + ", so nothing was sent. "
                        + "For other terms write '" + EXAMPLE_SLOT + "'; to end the negotiation press Reject.");
    }

    /** A number the pre-pass could not read as a term, as the player wrote it. */
    private static Optional<String> otherNumber(String text, Collection<String> rasaTerms) {
        for (String term : rasaTerms) {
            if (term != null && !term.isBlank()) {
                return Optional.of("\"" + term.trim() + "\"");
            }
        }
        if (text == null) {
            return Optional.empty();
        }
        Matcher digits = DIGITS.matcher(text);
        if (digits.find()) {
            return Optional.of(digits.group());
        }
        List<String> run = new ArrayList<>();
        for (String word : NOT_A_LETTER.split(text.toLowerCase(Locale.ROOT))) {
            if (NUMBER_WORDS.contains(word)) {
                run.add(word);
            } else if (!run.isEmpty()) {
                break;
            }
        }
        return run.isEmpty() ? Optional.empty() : Optional.of("\"" + String.join(" ", run) + "\"");
    }

    /**
     * S19 (playtest 5, PT27): whether the line asks for the Assistant's suggestion - "hint",
     * "suggest", "advice", "what should I do" and their plain variants - read WHOLE, so a line that
     * also says something else ("I suggest 2000 for 6h", "hint 2000") is never one. Read before the
     * grammar and Rasa: Rasa took "hint" for a refusal (0.86) and the vessel was sent away.
     */
    static boolean isHintRequest(String text) {
        if (text == null || DIGITS.matcher(text).find()) {
            return false;
        }
        String words = NOT_A_LETTER.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (words.startsWith("please ")) {
            words = words.substring("please ".length());
        }
        if (words.endsWith(" please")) {
            words = words.substring(0, words.length() - " please".length());
        }
        return HINT_REQUESTS.contains(words);
    }

    /** S19 (PT27): the four decided requests and their plain variants, as {@link #isHintRequest}
     * normalises a line (lower case, letters only, one space between words, no "please"). */
    private static final Set<String> HINT_REQUESTS = Set.of(
            "hint", "hints", "a hint", "give me a hint", "any hint", "any hints",
            "suggest", "suggestion", "suggestions", "any suggestion", "any suggestions", "suggest something",
            "what do you suggest", "what would you suggest",
            "advice", "advise", "advise me", "any advice", "some advice", "give me advice", "your advice",
            "what should i do", "what should i do now", "what should i do here", "what do i do",
            "what do i do now");

    /**
     * Decision N8: the chat's help line tells the player to type "status". The one word is read
     * here, so it works whether or not a classifier is running.
     */
    static boolean isStatusWord(String text) {
        return text != null && "status".equals(NOT_A_LETTER.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(""));
    }

    /**
     * Fills {@link #EXAMPLE_SLOT} with a tab's own example, or with {@link #GENERIC_EXAMPLE}
     * when there is none. A message without the slot comes back unchanged.
     */
    public static String fillExample(String message, String example) {
        if (message == null) {
            return null;
        }
        return message.replace(EXAMPLE_SLOT, example == null || example.isBlank() ? GENERIC_EXAMPLE : example);
    }

    /** The amount a {@code propose}/{@code counter} frame demands; {@code null} for any other
     * frame or an amount that cannot be read. */
    private static Double demandedAmount(Frame frame) {
        if (!"propose".equals(frame.move()) && !"counter".equals(frame.move())) {
            return null;
        }
        return amountOf(frame.element("money"));
    }

    private static boolean usable(Double offer) {
        return offer != null && offer > 0 && !offer.isInfinite();
    }

    /** A frame's {@code money} is {@code {amount, currency}} (see {@link Frame}); tolerate a bare number. */
    private static Double amountOf(Object money) {
        Object amount = money instanceof Map<?, ?> m ? m.get("amount") : money;
        if (!(amount instanceof Number n)) {
            return null;
        }
        double value = n.doubleValue();
        return Double.isNaN(value) ? null : value;
    }

    /** {@code €1436}, as the offer line writes it; cents only when there are any. */
    static String euros(double amount) {
        return amount == Math.rint(amount) && Math.abs(amount) < 1e15
                ? "€" + (long) amount
                : String.format(Locale.ROOT, "€%.2f", amount);
    }
}
