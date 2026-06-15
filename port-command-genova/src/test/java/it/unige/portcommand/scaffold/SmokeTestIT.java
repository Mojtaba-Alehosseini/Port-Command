package it.unige.portcommand.scaffold;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Full smoke test as a JUnit 5 integration test: JADE container boot + DF agent
 * + JPL query + clean shutdown, asserting no exception escapes {@link SmokeTest#run()}.
 */
@Tag("integration")
class SmokeTestIT {

    @Test
    void smokeTestRunsCleanly() {
        assertDoesNotThrow(SmokeTest::run);
    }
}
