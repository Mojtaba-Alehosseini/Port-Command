package it.unige.portcommand.bootstrap;

/** Unchecked wrapper for a JADE agent-spawn failure (e.g. {@code StaleProxyException}). */
public class AgentSpawnException extends RuntimeException {

    public AgentSpawnException(String message, Throwable cause) {
        super(message, cause);
    }
}
