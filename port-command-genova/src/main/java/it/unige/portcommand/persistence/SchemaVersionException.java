package it.unige.portcommand.persistence;

/**
 * A save file declared a {@code schemaVersion} this build does not read (task 22 hard
 * constraint: version 1 only, no migration — unknown versions are a hard, user-facing
 * failure, never a best-effort parse).
 */
public class SchemaVersionException extends Exception {

    private final int foundVersion;

    public SchemaVersionException(int foundVersion) {
        super("Save file from incompatible version (schemaVersion " + foundVersion
                + ", expected " + SaveLoadManager.SCHEMA_VERSION + "); please start a new game");
        this.foundVersion = foundVersion;
    }

    public int foundVersion() {
        return foundVersion;
    }
}
