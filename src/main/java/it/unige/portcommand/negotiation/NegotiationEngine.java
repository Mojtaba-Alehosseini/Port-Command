package it.unige.portcommand.negotiation;

/**
 * The price-decision surface a walk-in vessel calls once per player offer. The real
 * implementation (round limits, personality concessions, time limits) is task 15;
 * this task introduces the interface and wires it in. Production binds
 * {@link NoOpNegotiationEngine} (fails loudly); tests inject a Mockito mock.
 */
public interface NegotiationEngine {

    /**
     * Decide how the vessel responds to {@code playerPrice} given its hidden
     * {@code state}. Pure with respect to the agent — must not mutate {@code state}
     * or perform I/O.
     */
    Decision evaluate(double playerPrice, WalkInState state);
}
