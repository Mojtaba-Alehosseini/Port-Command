package it.unige.portcommand.persistence;

/**
 * The save file failed to parse or violated a schema invariant (truncated JSON, wrong
 * types, an unknown vessel type or channel, a missing file). Task 22 hard constraint:
 * this surfaces as one clean user-facing error and the load aborts — never a half-boot.
 */
public class CorruptSaveException extends Exception {

    public CorruptSaveException(String message) {
        super(message);
    }

    public CorruptSaveException(String message, Throwable cause) {
        super(message, cause);
    }
}
