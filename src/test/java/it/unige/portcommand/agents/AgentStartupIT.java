package it.unige.portcommand.agents;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boots a real JADE container, spawns the roster, and asserts every DF service is
 * registered with the expected instance count — then proves a clean restart
 * re-registers. Uses port 18099 to dodge a local 1099 conflict.
 */
@Tag("integration")
class AgentStartupIT {

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
    void rosterRegistersEveryServiceWithExpectedCounts() throws Exception {
        boot = new JadeBootstrap();
        boot.start(testConfig());
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact());

        Map<String, Integer> counts = census();
        assertEquals(1, counts.get("harbour-master"), "HarbourMaster singleton");
        assertEquals(2, counts.get("terminal"), "two terminals");
        assertEquals(4, counts.get("tug-escort"), "four tugs");
        assertEquals(1, counts.get("customs"), "Customs singleton");
        assertEquals(1, counts.get("weather"), "Weather singleton");
        assertEquals(1, counts.get("assistant"), "Assistant singleton");
        assertEquals(0, counts.get("vessel"), "no vessels at boot (spawned dynamically)");
    }

    @Test
    void restartReRegistersCleanly() throws Exception {
        boot = new JadeBootstrap();
        boot.start(testConfig());
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact());
        census();
        boot.shutdown();

        boot.start(testConfig());
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact());
        Map<String, Integer> counts = census();
        assertEquals(4, counts.get("tug-escort"), "tugs re-register after restart");
        assertEquals(1, counts.get("harbour-master"), "HM re-registers after restart");
    }

    /** Spawns a probe that censuses the DF after a settle delay; blocks on its result. */
    private Map<String, Integer> census() throws Exception {
        CompletableFuture<Map<String, Integer>> future = new CompletableFuture<>();
        boot.getSpawner().spawn("df_census_probe", DfCensusProbe.class, new Object[] {future});
        return future.get(15, TimeUnit.SECONDS);
    }
}
