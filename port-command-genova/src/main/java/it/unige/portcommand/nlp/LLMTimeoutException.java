package it.unige.portcommand.nlp;

/**
 * Completes {@link LLMBridge#explain}'s future exceptionally when the Flask sidecar call
 * exceeds {@code llm.timeout_ms}. {@code LLMBridge} has no access to the recommendation
 * trace (top rule fired, chosen action, …), so it cannot build the template text itself —
 * that is the caller's job, per planning/10 §"LLM call": "on timeout use the template text
 * directly". Since S12 the Hint behaviour ({@code RecommendOnDemandBehaviour}) shows the
 * template BEFORE it calls the bridge, so this exception only means the model's wording never
 * replaces it; {@code ExplainEventBehaviour} still substitutes its template on failure.
 */
public class LLMTimeoutException extends NlpServiceException {

    public LLMTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
