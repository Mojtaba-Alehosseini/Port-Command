package it.unige.portcommand.negotiation;

/**
 * The price-decision surface a walk-in vessel calls once per player offer. Production binds
 * {@link RealNegotiationEngine} (task 15, constructed per-vessel by
 * {@code PoissonSpawnBehaviour}); tests inject a Mockito mock or their own instance.
 * {@link NoOpNegotiationEngine} (fails loudly) remains as the documented "unwired" guard.
 */
public interface NegotiationEngine {

    /**
     * Decide how the vessel responds to {@code playerPrice} given its hidden
     * {@code state}. Pure with respect to the agent — must not mutate {@code state}
     * or perform I/O.
     */
    Decision evaluate(double playerPrice, WalkInState state);
}
