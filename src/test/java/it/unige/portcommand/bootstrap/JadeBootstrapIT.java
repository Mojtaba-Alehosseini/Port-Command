package it.unige.portcommand.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots a real JADE Main Container, asserts the DF is reachable (an empty
 * {@code smoke-test} lookup from the probe agent) and the container/clock/spawner
 * are wired, then shuts down — and proves a clean start→shutdown→start→shutdown
 * cycle. Uses port 18099 to dodge a local 1099 (RMI registry / IDE) conflict.
 */
@Tag("integration")
class JadeBootstrapIT {

    private static final int TEST_PORT = 18099;

    private JadeBootstrap boot;

    private static BootstrapConfig testConfig() {
        return new BootstrapConfig(TEST_PORT, false, "realtime", 300);
    }

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void bootsAndShutsDown() {
        boot = new JadeBootstrap();
        boot.start(testConfig());

        assertNotNull(boot.getMainContainer(), "main container present after start");
        assertNotNull(boot.getSimClock(), "sim clock present after start");
        assertNotNull(boot.getSpawner(), "spawner present after start");
        assertTrue(boot.bootDfSelfCheck().isEmpty(), "no smoke-test providers at boot");
        assertNotNull(boot.getRasaBridge(), "rasa bridge present after start (no network I/O at construction)");
        assertNotNull(boot.getLLMBridge(), "llm bridge present after start (no network I/O at construction)");

        boot.shutdown();
        assertFalse(boot.isStarted(), "stopped after shutdown");
    }

    @Test
    void restartsCleanly() {
        boot = new JadeBootstrap();
        boot.start(testConfig());
        boot.shutdown();

        // Second cycle must not raise a port-in-use or Runtime-reuse error.
        boot.start(testConfig());
        assertNotNull(boot.getMainContainer());
        boot.shutdown();
        assertFalse(boot.isStarted());
    }
}
