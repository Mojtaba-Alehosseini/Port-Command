package it.unige.portcommand.scenario;

/**
 * A scenario JSON parsed but violates the schema invariants (unsorted events, unknown
 * vessel type, dangling contract ref, …). Checked — the {@code NewGameDialog}/CLI
 * surface the message; a malformed packaged scenario fails the build via
 * {@code ScenarioLoaderTest}.
 */
public class ScenarioValidationException extends Exception {

    public ScenarioValidationException(String message) {
        super(message);
    }
}
