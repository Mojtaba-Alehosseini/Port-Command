package it.unige.portcommand.negotiation;

/**
 * Placeholder production binding so the module compiles before task 15 lands. Its
 * {@link #evaluate} <b>throws</b> — a vessel reaching it in production is a wiring
 * bug that must fail loudly, never silently auto-accept. Tests inject a Mockito mock
 * instead; the real engine replaces this binding in task 15.
 */
public final class NoOpNegotiationEngine implements NegotiationEngine {

    @Override
    public Decision evaluate(double playerPrice, WalkInState state) {
        throw new UnsupportedOperationException(
                "NegotiationEngine not wired (task 15). NoOpNegotiationEngine must never decide a real negotiation.");
    }
}
