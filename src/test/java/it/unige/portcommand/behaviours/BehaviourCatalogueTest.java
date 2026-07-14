package it.unige.portcommand.behaviours;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jade.core.behaviours.Behaviour;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the locked behaviour catalogue (INVARIANTS.md / ADR-01): exactly 51
 * stub classes across 5 packages, each a {@link Behaviour}, and NO {@code PlayerAgent}
 * (CLAUDE.md rule 2). Reflectively scans the compiled package dirs so any drift in
 * the count fails the build — downstream tasks fill bodies, never add classes.
 */
class BehaviourCatalogueTest {

    private static final Map<String, Integer> EXPECTED_PER_PACKAGE = Map.of(
            "auto_flow", 7,
            "negotiation", 7,
            "cnp", 6,
            "assistant", 4,
            "coordination", 27);

    private static final int LOCKED_TOTAL = 51;

    @Test
    void exactlyFiftyOneBehaviourStubsAllExtendingBehaviour() throws Exception {
        int total = 0;
        for (Map.Entry<String, Integer> e : EXPECTED_PER_PACKAGE.entrySet()) {
            List<Class<?>> classes = behaviourClassesIn(e.getKey());
            assertEquals(e.getValue(), classes.size(),
                    "behaviour count in package '" + e.getKey() + "'");
            for (Class<?> c : classes) {
                assertTrue(Behaviour.class.isAssignableFrom(c),
                        c.getName() + " must extend jade.core.behaviours.Behaviour");
            }
            total += classes.size();
        }
        assertEquals(LOCKED_TOTAL, total, "locked behaviour-stub count (ADR-01)");
    }

    @Test
    void noPlayerAgentClassExists() throws Exception {
        URL url = getClass().getClassLoader().getResource("it/unige/portcommand/agents");
        assertNotNull(url, "agents package must be on the classpath");
        try (var stream = Files.list(Path.of(url.toURI()))) {
            boolean anyPlayer = stream
                    .map(p -> p.getFileName().toString())
                    .anyMatch(n -> n.contains("PlayerAgent"));
            assertFalse(anyPlayer, "there must be no PlayerAgent (CLAUDE.md rule 2)");
        }
    }

    private List<Class<?>> behaviourClassesIn(String pkg) throws Exception {
        String pkgPath = "it/unige/portcommand/behaviours/" + pkg;
        URL url = getClass().getClassLoader().getResource(pkgPath);
        assertNotNull(url, "package dir on classpath: " + pkgPath);
        List<Class<?>> out = new ArrayList<>();
        try (var stream = Files.list(Path.of(url.toURI()))) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String fileName = p.getFileName().toString();
                if (fileName.endsWith(".class") && !fileName.contains("$")) {
                    String className = "it.unige.portcommand.behaviours." + pkg + "."
                            + fileName.substring(0, fileName.length() - ".class".length());
                    out.add(Class.forName(className));
                }
            }
        }
        return out;
    }
}
