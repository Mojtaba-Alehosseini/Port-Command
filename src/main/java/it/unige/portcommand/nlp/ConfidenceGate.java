package it.unige.portcommand.nlp;

/**
 * Routes a Rasa intent confidence to one of three branches. Boundaries are locked
 * (PROJECT_DEFINITION.md §6.1, planning/14 §14.4) and half-open — every confidence in
 * {@code [0,1]} maps to exactly one branch, no overlap, no gap. The upper boundary of a
 * range belongs to the higher branch: {@code 0.60 -> A}, {@code 0.40 -> B}, {@code 0.3999 -> C}.
 */
public final class ConfidenceGate {

    public static final double HIGH_THRESHOLD = 0.60;
    public static final double MEDIUM_THRESHOLD = 0.40;

    public enum Branch {
        /** confidence &ge; 0.60 — map straight to an action/ACL. */
        A_HIGH_CONFIDENCE,
        /** 0.40 &le; confidence &lt; 0.60 — DCG-only territory; not confident enough for Rasa alone. */
        B_MEDIUM_DCG_ONLY,
        /** confidence &lt; 0.40 — ask the player to clarify. */
        C_CLARIFICATION
    }

    public Branch route(double confidence) {
        if (confidence >= HIGH_THRESHOLD) {
            return Branch.A_HIGH_CONFIDENCE;
        }
        if (confidence >= MEDIUM_THRESHOLD) {
            return Branch.B_MEDIUM_DCG_ONLY;
        }
        return Branch.C_CLARIFICATION;
    }
}
