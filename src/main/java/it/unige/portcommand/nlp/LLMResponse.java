package it.unige.portcommand.nlp;

/**
 * Result of {@link LLMBridge#explain}: generated prose plus the sidecar's hallucination-
 * validator verdict. {@code validated} is {@code false} when the caller did not request
 * validation (the sidecar returns JSON {@code null} in that case). Per task 13/14 note: this
 * verdict is weaker than the Java-side check (it lacks the positive-control {@code
 * allFigures()} pass, which stays Java-side) — advisory only.
 */
public record LLMResponse(String text, boolean validated) {
}
