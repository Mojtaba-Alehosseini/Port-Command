package it.unige.portcommand.prolog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit coverage of {@link PrologEngine}: init consults exactly the six files,
 * the rule kernel is exactly 30 rules (R1–R30), and concurrent access neither
 * deadlocks nor throws. Exercises the real embedded SWI-Prolog engine via JPL.
 */
class PrologEngineTest {

    private static final Pattern RULE_HEADER = Pattern.compile("^% RULE (R\\d+):");

    private static final List<String> RULE_MODULES = List.of(
            "/prolog/rules_compatibility.pl",
            "/prolog/rules_customs.pl",
            "/prolog/rules_escort.pl",
            "/prolog/rules_priority.pl",
            "/prolog/rules_weather.pl");

    @BeforeAll
    static void initEngine() {
        PrologEngine.getInstance().init();
    }

    @Test
    void getInstanceIsSingletonAndInitialised() {
        assertSame(PrologEngine.getInstance(), PrologEngine.getInstance());
        assertTrue(PrologEngine.getInstance().isInitialised(), "init() must mark the engine initialised");
    }

    @Test
    void manifestIsOntologyFirstThenFiveRuleModules() {
        List<String> manifest = PrologEngine.resourceManifest();
        assertEquals(6, manifest.size(), "exactly 6 .pl files");
        assertEquals("/prolog/port_ontology.pl", manifest.get(0), "ontology consulted FIRST");
        List<String> rest = manifest.subList(1, manifest.size());
        assertEquals(RULE_MODULES, rest, "the 5 rule modules, alphabetically, after the ontology");
        assertTrue(rest.stream().noneMatch(r -> r.contains("smoke")), "smoke.pl must NOT be consulted");
    }

    @Test
    void initConsultsAllSixFiles() {
        PrologEngine e = PrologEngine.getInstance();
        // One sentinel per file proves it loaded into the user module.
        assertTrue(e.hasSolution("vessel_type(tanker)"), "port_ontology.pl");
        assertTrue(e.hasSolution("current_predicate(compatible/2)"), "rules_compatibility.pl");
        assertTrue(e.hasSolution("current_predicate(clearance_ok/2)"), "rules_customs.pl");
        assertTrue(e.hasSolution("current_predicate(tugs_required/2)"), "rules_escort.pl");
        assertTrue(e.hasSolution("priority_rank(emergency, 1)"), "rules_priority.pl");
        assertTrue(e.hasSolution("wind_limit(tanker, 30)"), "rules_weather.pl");
    }

    @Test
    void ruleKernelIsExactlyThirtyRules() throws Exception {
        Set<String> ruleIds = new LinkedHashSet<>();
        int headerCount = 0;
        for (String module : RULE_MODULES) {
            Path path = Path.of(PrologEngineTest.class.getResource(module).toURI());
            for (String line : Files.readAllLines(path)) {
                Matcher m = RULE_HEADER.matcher(line.trim());
                if (m.find()) {
                    headerCount++;
                    ruleIds.add(m.group(1));
                }
            }
        }
        assertEquals(30, headerCount, "exactly 30 '% RULE Rn:' headers across the 5 modules");
        assertEquals(30, ruleIds.size(), "30 DISTINCT rule ids (no duplicate Rn header)");
        Set<String> expected = new LinkedHashSet<>();
        for (int i = 1; i <= 30; i++) {
            expected.add("R" + i);
        }
        assertEquals(expected, ruleIds, "rule ids must be exactly R1..R30");
    }

    @Test
    void mainResourceDirHoldsExactlyTheFiveRuleModules() throws Exception {
        // Anchor to the ontology's directory (the main resources dir) and confirm
        // no stray rules_*.pl has crept in beyond the manifest's five.
        Path prologDir = Path.of(PrologEngineTest.class.getResource("/prolog/port_ontology.pl").toURI())
                .getParent();
        try (var stream = Files.list(prologDir)) {
            Set<String> ruleFiles = new java.util.TreeSet<>();
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("rules_") && n.endsWith(".pl"))
                    .forEach(ruleFiles::add);
            assertEquals(
                    Set.of("rules_compatibility.pl", "rules_customs.pl", "rules_escort.pl",
                            "rules_priority.pl", "rules_weather.pl"),
                    ruleFiles,
                    "exactly the 5 canonical rule modules on disk (30-rule invariant)");
        }
    }

    @Test
    void concurrentQueriesDoNotDeadlockOrThrow() {
        final int threads = 8;
        final int iterations = 25;
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        try {
                            startGate.await();
                            for (int i = 0; i < iterations; i++) {
                                PrologQueries.isCompatible("berth_1", "tanker", 14.0, 140.0, 20000);
                                PrologQueries.tugsRequired("tanker", 15000, 140.0);
                                PrologQueries.operationSafe("tanker", 28.0, "good");
                                PrologQueries.clearanceOk("tanker", "hazmat_class_3");
                                PrologQueries.priorityRank("emergency");
                                PrologQueries.selectBestBids(List.of(
                                        new TugBid("tug_a", 500, 6.0, 0.8),
                                        new TugBid("tug_b", 400, 5.0, 0.9)), 1);
                            }
                        } catch (Throwable th) {
                            failures.add(th);
                        }
                    }));
                }
                startGate.countDown(); // fire all threads simultaneously
                for (Future<?> f : futures) {
                    f.get(25, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            if (!failures.isEmpty()) {
                fail("concurrent access raised " + failures.size() + " failure(s); first: "
                        + failures.get(0), failures.get(0));
            }
        });
    }
}
