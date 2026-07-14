package it.unige.portcommand.ontology;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task-02 obligations for {@link PortOntology}: the singleton constructs (its
 * constructor asserts {@code port_ontology.pl} is on the classpath) and
 * {@code consultOntologyFile()} resolves to a real file. The actual JPL consult
 * is wired in task 04.
 */
class PortOntologyTest {

    @Test
    void getInstanceIsSingletonAndValidatesClasspath() {
        PortOntology a = PortOntology.getInstance();
        PortOntology b = PortOntology.getInstance();
        assertSame(a, b, "getInstance must return the same instance");
    }

    @Test
    void consultOntologyFileResolvesToExistingPl() {
        String resolved = PortOntology.getInstance().consultOntologyFile();
        assertTrue(resolved.endsWith("port_ontology.pl"), "resolved path: " + resolved);
        assertTrue(Files.exists(Path.of(resolved)), "file should exist on disk: " + resolved);
    }

    @Test
    void initStubIsCallable() {
        // Task-02 stub: verifies the ontology is present and logs; the real JPL
        // consult (with a typed PrologEngine) arrives in task 04.
        assertDoesNotThrow(() -> PortOntology.getInstance().init(null));
    }
}
