package it.unige.portcommand.nlp;

/** Unchecked wrapper for a Rasa/LLM sidecar HTTP failure (bad status, malformed JSON, I/O error). */
public class NlpServiceException extends RuntimeException {

    public NlpServiceException(String message) {
        super(message);
    }

    public NlpServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
