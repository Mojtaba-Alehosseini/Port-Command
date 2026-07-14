package it.unige.portcommand.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON marshalling for ACL message content. Lives in {@code core} (next to
 * {@link MessageFactory}) rather than a behaviour package so the behaviour-catalogue
 * invariant — behaviour packages contain only behaviours — stays clean. The
 * {@code read} variants return {@code null} on any failure so callers can map a bad
 * payload to a REFUSE("malformed") without try/catch noise.
 */
public final class TerminalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TerminalJson() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize message content: " + value, e);
        }
    }

    public static <T> T readOrNull(String content, Class<T> type) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(content, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static JsonNode readTreeOrNull(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
