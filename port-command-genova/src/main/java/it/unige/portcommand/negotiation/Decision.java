package it.unige.portcommand.negotiation;

/**
 * The outcome of {@link NegotiationEngine#evaluate} for one player offer. Exactly
 * three types (per task 07 §88):
 *
 * <ul>
 *   <li>{@code ACCEPT} — take the player's price; the behaviour replies ACCEPT_PROPOSAL.</li>
 *   <li>{@code COUNTER} — propose {@code newCounter}; the behaviour replies with a
 *       <b>PROPOSE</b> (a counter-offer IS a fresh proposal in FIPA terms, never a
 *       REJECT_PROPOSAL).</li>
 *   <li>{@code WITHDRAW} — leave the negotiation (reason e.g. {@code player_refused}).</li>
 * </ul>
 *
 * <p>{@code REFUSE_INCOMPATIBLE} is intentionally absent — physical incompatibility is
 * decided by an explicit {@code PrologQueries.isCompatible} check <i>before</i> the
 * engine is consulted, so the engine never returns it.
 */
public record Decision(Type type, double newCounter, String reason) {

    public enum Type {
        ACCEPT,
        COUNTER,
        WITHDRAW
    }

    public static Decision accept(String reason) {
        return new Decision(Type.ACCEPT, 0.0, reason);
    }

    public static Decision counter(double newCounter, String reason) {
        return new Decision(Type.COUNTER, newCounter, reason);
    }

    public static Decision withdraw(String reason) {
        return new Decision(Type.WITHDRAW, 0.0, reason);
    }
}
