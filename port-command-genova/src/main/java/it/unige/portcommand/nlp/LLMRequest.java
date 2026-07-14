package it.unige.portcommand.nlp;

import java.util.List;

/**
 * Request payload for {@link LLMBridge#explain}, mirroring the Flask sidecar's
 * {@code POST /explain} body (task 13): {@code prompt} is required; {@code system},
 * {@code requiredNumbers}/{@code requiredEntities} and {@code validate} are the optional
 * hallucination-validator inputs (used only when {@code validate=true}).
 */
public record LLMRequest(
        String prompt,
        String system,
        List<String> requiredNumbers,
        List<String> requiredEntities,
        boolean validate) {

    public LLMRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        requiredNumbers = requiredNumbers == null ? List.of() : List.copyOf(requiredNumbers);
        requiredEntities = requiredEntities == null ? List.of() : List.copyOf(requiredEntities);
    }

    /** A plain prompt with no validator inputs and {@code validate=false}. */
    public static LLMRequest of(String prompt) {
        return new LLMRequest(prompt, null, List.of(), List.of(), false);
    }
}
