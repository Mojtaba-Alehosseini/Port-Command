package it.unige.portcommand.assistant;

import java.util.Locale;
import java.util.Optional;

import it.unige.portcommand.harbourmaster.financial.ExpenseRules;

/**
 * The Assistant's own words (S17a, ADR-21, playtest item PT13): the plain sentence a Hint opens
 * with, the details line under it, and the line the autopilot writes when a move of its has been
 * made. Pure — every text is a function of the recommendation and the snapshot it was made from.
 *
 * <p><b>Why this is not {@link AssistantPromptBuilder}.</b> That class writes the paragraph the
 * model is asked to paraphrase, and its figures are the hallucination validator's contract
 * ({@link Recommendation#allFigures()}): every one of them has to be there. It reads accordingly —
 * "Recommended reject silently for cargo_vessel at berth_1 (17h). Market history ... Acceptance
 * probability 0%, expected value €0. Berth berth_1 is compatible." — and until S17a that same
 * paragraph was what the player saw first. The prompt and the validator are unchanged; what the
 * player sees first is now a sentence written for a player, with the numbers moved to the
 * details line.
 *
 * <p><b>The details line is where rule ids and the weather live</b> (tree-analysis decision A4).
 * They are never sent to the model: the validator would — rightly — reject a wind figure or an
 * "R15" as something the recommendation did not contain, and the text would always fall back.
 *
 * <p>Money is shown as whole euros with no thousands separator ({@code €1529}), like the tab's
 * own offer line; {@link Locale#ROOT} keeps the digits the same on every machine.
 */
public final class AssistantWording {

    /** What stands in front of every line the autopilot writes into a chat tab. */
    public static final String AUTOPILOT_PREFIX = "🤖 Assistant (autopilot): ";

    /** The reason given for "say no" when the offer simply does not pay (playtest item PT13). */
    static final String COSTS_ARE_HIGHER = "costs (berth time, tugs, customs) are higher than this offer";

    private AssistantWording() {
    }

    // ---- the Hint -------------------------------------------------------------------------------

    /**
     * The sentence a Hint shows at once: what to do, in the words the buttons and the input use.
     * It is what the model's validated wording later replaces, and it is complete without it.
     */
    public static String hintText(Recommendation rec, WalkInDialogueSnapshot snapshot) {
        if (RecommendationCandidate.ACCEPT.equals(rec.action())) {
            return "Accept their offer of " + euros(rec.price()) + " for " + snapshot.durationHours() + "h.";
        }
        if (RecommendationCandidate.COUNTER.equals(rec.action())) {
            return "Counter at " + euros(rec.price()) + " for " + snapshot.durationHours() + "h.";
        }
        return "Say no — " + sayNoReason(rec) + ".";
    }

    /** The recommendation in a few words, for the hint row and the "Use this" tooltip. */
    public static String suggestion(Recommendation rec, WalkInDialogueSnapshot snapshot) {
        if (RecommendationCandidate.ACCEPT.equals(rec.action())) {
            return "accept " + euros(rec.price()) + " for " + snapshot.durationHours() + "h";
        }
        if (RecommendationCandidate.COUNTER.equals(rec.action())) {
            return "counter at " + euros(rec.price()) + " for " + snapshot.durationHours() + "h";
        }
        return "say no";
    }

    /**
     * The details line: the numbers the sentence leaves out, the Prolog check behind the
     * recommendation, and — when the weather would keep this vessel waiting outside after a
     * deal — that, with its rule ids ({@link WeatherNote#forDetails}).
     *
     * <ul>
     *   <li>accept / counter: {@code EV €640 · chance 62% · R7 ✓}</li>
     *   <li>say no because it does not pay: {@code offer €1477 · costs €1650 · R7 ✓}</li>
     *   <li>say no because the ship does not fit the berth: {@code R7 ✗}</li>
     * </ul>
     *
     * @param weatherWait the weather part, already worded, or empty when the weather is safe for
     *                    the vessel (or unknown)
     */
    public static String details(Recommendation rec, WalkInDialogueSnapshot snapshot, Optional<String> weatherWait) {
        StringBuilder line = new StringBuilder();
        boolean compatible = isCompatible(rec);
        if (!compatible) {
            line.append("R7 ✗");
        } else if (RecommendationCandidate.REJECT_SILENT.equals(rec.action())) {
            line.append("offer ").append(euros(snapshot.lastVesselOffer()))
                    .append(" · costs ").append(euros(costOf(snapshot)))
                    .append(" · R7 ✓");
        } else {
            line.append("EV ").append(euros(rec.ev()))
                    .append(" · chance ").append(String.format(Locale.ROOT, "%.0f", rec.pAccept() * 100.0)).append('%')
                    .append(" · R7 ✓");
        }
        weatherWait.filter(text -> !text.isBlank()).ifPresent(text -> line.append(" · ").append(text));
        return line.toString();
    }

    // ---- the autopilot's narration --------------------------------------------------------------

    /**
     * The line written into the tab once a move of the autopilot's HAS been made (it is published
     * after the HarbourMaster dispatched the command — decision A9 — never before the Prolog gate
     * has judged it).
     *
     * @param policyTrigger the player's policy that decided the move, or {@code null} when the
     *                      expected-value calculation did
     * @param rec           the recommendation behind an EV move; {@code null} for a policy move
     */
    public static String autopilotMove(String action, double price, String policyTrigger, Recommendation rec) {
        String because = policyTrigger == null ? "" : " (your policy: " + policyTrigger + ")";
        if (RecommendationCandidate.ACCEPT.equals(action)) {
            return AUTOPILOT_PREFIX + "accepted their offer of " + euros(price) + because + ".";
        }
        if (RecommendationCandidate.COUNTER.equals(action)) {
            return AUTOPILOT_PREFIX + "countered at " + euros(price) + because + ".";
        }
        if (policyTrigger != null) {
            return AUTOPILOT_PREFIX + "said no" + because + ".";
        }
        return AUTOPILOT_PREFIX + "said no — " + (rec == null ? "this offer does not pay" : sayNoReason(rec)) + ".";
    }

    /** The line for the "no" that follows a move the Prolog gate refused twice. */
    public static String autopilotGaveUp() {
        return AUTOPILOT_PREFIX + "said no — the port's rules refused the move.";
    }

    // ---- shared ---------------------------------------------------------------------------------

    private static String sayNoReason(Recommendation rec) {
        return isCompatible(rec) ? COSTS_ARE_HIGHER : "this ship does not fit " + berthLabel(rec.berthId());
    }

    private static boolean isCompatible(Recommendation rec) {
        return "compatible".equals(rec.prologStatus());
    }

    /** The cost the algorithm set against the offer: the same call, on the same snapshot. */
    static double costOf(WalkInDialogueSnapshot snapshot) {
        return ExpenseRules.marginalCost(snapshot.vesselType(), snapshot.cargoClass(),
                snapshot.durationHours(), snapshot.tonnage());
    }

    /** {@code berth 1} for {@code berth_1}: the way the Port table and the comm log name a berth. */
    static String berthLabel(String berthId) {
        return berthId == null ? "the berth" : berthId.replace('_', ' ');
    }

    /** Whole euros, no thousands separator: {@code €1529}. */
    public static String euros(double amount) {
        return "€" + String.format(Locale.ROOT, "%.0f", amount);
    }
}
