package it.unige.portcommand.artifacts;

import java.util.Objects;
import java.util.function.Predicate;

import it.unige.portcommand.assistant.RecommendationCandidate;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;

/**
 * A parsed player autopilot policy (e.g. "auto accept if price &gt; 2200 for tankers"),
 * produced by {@code nlp.PolicyParser} (planning/10 §10.5b) and consumed by
 * {@code AutopilotExecuteBehaviour}'s policy-precedence-before-EV check (§10.7).
 *
 * @param trigger     the original player-typed text, kept for the HUD "Policy registered: …" notice
 *                    and for {@code GameState.policyTriggers} (the save re-parses this string)
 * @param predicate   observable-only condition over a {@link WalkInDialogueSnapshot} — the same
 *                    privacy constraint as {@link it.unige.portcommand.assistant.RecommendationAlgorithm}
 * @param action      what to do when {@code predicate} matches
 * @param description a human-readable rendering of what was UNDERSTOOD (2026-07-28, C-01/D-02). The
 *                    Settings field echoes it so the player sees the interpretation rather than
 *                    their own words read back: a rule only has a description because its action,
 *                    its condition clauses and its optional vessel-type scope all parsed. The
 *                    {@code predicate} itself is a composed lambda and cannot be introspected,
 *                    which is why this is captured at parse time.
 *                    <p><b>Precisely:</b> the action and the vessel-type scope are the PARSED values
 *                    (the scope is the canonical type, not the plural the player typed); the
 *                    condition is the player's own clause text, which is constrained to the DSL —
 *                    it is reached only once every {@code and}-separated clause matched the anchored
 *                    condition pattern — but its CASING passes through, so
 *                    {@code if PRICE > 2200} echoes as {@code when PRICE > 2200}. An earlier version
 *                    of this javadoc said "not from the input string" flatly; the adversarial review
 *                    was right that that overstated it.
 */
public record PolicyRule(String trigger, Predicate<WalkInDialogueSnapshot> predicate,
                         PolicyAction action, String description) {

    public PolicyRule {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(description, "description");
    }

    /** The autopilot action a matched policy dictates; reuses {@link RecommendationCandidate}'s
     * action vocabulary so the two never drift. */
    public record PolicyAction(String kind, double counterPct) {

        public static PolicyAction accept() {
            return new PolicyAction(RecommendationCandidate.ACCEPT, 0.0);
        }

        public static PolicyAction rejectSilent() {
            return new PolicyAction(RecommendationCandidate.REJECT_SILENT, 0.0);
        }

        public static PolicyAction counter(double pct) {
            return new PolicyAction(RecommendationCandidate.COUNTER, pct);
        }
    }
}
