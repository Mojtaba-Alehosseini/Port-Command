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
 * @param trigger   the original player-typed text, kept for the HUD "Policy registered: …" notice
 * @param predicate observable-only condition over a {@link WalkInDialogueSnapshot} — the same
 *                  privacy constraint as {@link it.unige.portcommand.assistant.RecommendationAlgorithm}
 * @param action    what to do when {@code predicate} matches
 */
public record PolicyRule(String trigger, Predicate<WalkInDialogueSnapshot> predicate, PolicyAction action) {

    public PolicyRule {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(action, "action");
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
