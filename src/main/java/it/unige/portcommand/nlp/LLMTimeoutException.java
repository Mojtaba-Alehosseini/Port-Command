package it.unige.portcommand.nlp;

/**
 * Completes {@link LLMBridge#explain}'s future exceptionally when the Flask sidecar call
 * exceeds {@code llm.timeout_ms}. {@code LLMBridge} has no access to the recommendation
 * trace (top rule fired, chosen action, …), so it cannot build the template-text fallback
 * itself — the caller (the Assistant's explain behaviour, task 10) catches this via
 * {@code .exceptionally(...)} and substitutes its own template text, per planning/10 §"LLM
 * call": "on timeout use the template text directly" (the caller's job, not the bridge's).
 */
public class LLMTimeoutException extends NlpServiceException {

    public LLMTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
