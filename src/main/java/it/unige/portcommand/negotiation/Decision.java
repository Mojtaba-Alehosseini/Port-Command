package it.unige.portcommand.negotiation;

/**
 * The outcome of {@link NegotiationEngine#evaluate} for one player offer. Exactly
 * three types (per task 07 §88):
 *
 * <ul>
 *   <li>{@code ACCEPT} — take the player's terms; the behaviour replies ACCEPT_PROPOSAL.
 *       {@code newHours} is the agreed duration (the player's proposed hours, engine-validated
 *       at or above the hidden floor — task 19b).</li>
 *   <li>{@code COUNTER} — propose {@code newCounter} for {@code newHours}; the behaviour replies
 *       with a <b>PROPOSE</b> (a counter-offer IS a fresh proposal in FIPA terms, never a
 *       REJECT_PROPOSAL). {@code newHours} echoes the player's hours when they are workable, or
 *       pushes back to the vessel's floor when they are not (§7.3 duration bargaining).</li>
 *   <li>{@code WITHDRAW} — leave the negotiation (reason e.g. {@code over_priced}).</li>
 * </ul>
 *
 * <p>{@code REFUSE_INCOMPATIBLE} is intentionally absent — physical incompatibility is
 * decided by an explicit {@code PrologQueries.isCompatible} check <i>before</i> the
 * engine is consulted, so the engine never returns it.
 */
public record Decision(Type type, double newCounter, int newHours, String reason) {

    public enum Type {
        ACCEPT,
        COUNTER,
        WITHDRAW
    }

    public static Decision accept(int agreedHours, String reason) {
        return new Decision(Type.ACCEPT, 0.0, agreedHours, reason);
    }

    public static Decision counter(double newCounter, int newHours, String reason) {
        return new Decision(Type.COUNTER, newCounter, newHours, reason);
    }

    public static Decision withdraw(String reason) {
        return new Decision(Type.WITHDRAW, 0.0, 0, reason);
    }
}
