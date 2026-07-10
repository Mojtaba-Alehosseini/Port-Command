package it.unige.portcommand.prolog;

import java.net.URL;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the five PLUnit suites in-process through the live JPL bridge, against
 * the same engine the agents use. This is the Java-side mirror of the standalone
 * {@code swipl} run: it proves the rule kernel is all-green when loaded by
 * {@link PrologEngine#init()} rather than by a hand-rolled consult script.
 *
 * <p>{@code run_tests/1} fails (no solution) if any test fails — verified against
 * SWI-Prolog 10.0.2 — so {@code hasSolution("run_tests(suite)")} is a genuine
 * pass/fail gate, not a rubber stamp.
 */
@Tag("integration")
class PrologRulesIT {

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    // "spawnability" (task 07b) rides the same JPL lane as the five task-04 rule-module
    // suites, proving the spawnability gate green in-process, not just under standalone swipl.
    @ParameterizedTest(name = "PLUnit suite ''{0}'' is all-green")
    @ValueSource(strings = {"compatibility", "customs", "escort", "priority", "spawnability", "weather"})
    void plunitSuitePasses(String suite) {
        PrologEngine e = PrologEngine.getInstance();
        String path = resolveTestSuite("/prolog/test_" + suite + ".pl");
        assertTrue(e.hasSolution("consult('" + path + "')"), "consult test_" + suite + ".pl");
        assertTrue(e.hasSolution("run_tests(" + suite + ")"),
                "PLUnit suite '" + suite + "' must report all tests passed");
    }

    private static String resolveTestSuite(String resource) {
        URL url = PrologRulesIT.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException(resource + " not on the test classpath");
        }
        try {
            return Path.of(url.toURI()).toString().replace('\\', '/');
        } catch (Exception ex) {
            throw new IllegalStateException("bad URI for " + resource + ": " + url, ex);
        }
    }
}
