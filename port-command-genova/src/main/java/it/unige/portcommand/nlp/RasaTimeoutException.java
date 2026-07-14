package it.unige.portcommand.nlp;

/**
 * Thrown by {@link RasaBridge#parse} when the {@code /model/parse} call exceeds its 3&nbsp;s
 * request timeout. Kept distinct from the general {@link NlpServiceException} so
 * {@link NLPPipeline} can catch <em>only</em> the timeout case and fall through to
 * clarification, while any other Rasa failure (4xx/5xx/malformed JSON) still surfaces as
 * a pipeline {@code Error}.
 */
public class RasaTimeoutException extends NlpServiceException {

    public RasaTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
