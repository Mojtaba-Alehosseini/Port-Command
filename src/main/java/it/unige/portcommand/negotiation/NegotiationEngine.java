package it.unige.portcommand.negotiation;

/**
 * The price-decision surface a walk-in vessel calls once per player offer. Production binds
 * {@link RealNegotiationEngine} (task 15, constructed per-vessel by
 * {@code PoissonSpawnBehaviour}); tests inject a Mockito mock or their own instance.
 * {@link NoOpNegotiationEngine} (fails loudly) remains as the documented "unwired" guard.
 */
public interface NegotiationEngine {

    /**
     * Decide how the vessel responds to the player's demanded fee {@code playerPrice} for a
     * {@code playerHours}-long stay, given its hidden {@code state} (task 19b: §7.3 negotiates
     * price AND duration; the caller resolves an utterance with no stated duration to the
     * standing hours term BEFORE calling — the engine always sees a concrete proposal). Pure
     * with respect to the agent — must not mutate {@code state} or perform I/O.
     */
    Decision evaluate(double playerPrice, int playerHours, WalkInState state);
}
