package it.unige.portcommand.prolog;

/**
 * Unchecked failure from the Prolog layer: a consult that did not succeed, a
 * query that unexpectedly produced no solution, or a term that could not be
 * decoded into the expected Java type.
 *
 * <p>Unchecked because agent plan bodies treat a Prolog malfunction as a
 * programming error (a malformed goal or a missing rule), not a recoverable
 * condition — the binding decision simply cannot be made and the caller aborts.
 */
public final class PrologException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PrologException(String message) {
        super(message);
    }

    public PrologException(String message, Throwable cause) {
        super(message, cause);
    }
}
