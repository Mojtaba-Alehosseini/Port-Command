package it.unige.portcommand;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;

/**
 * Application entry point. Boots the JADE Main Container and blocks; agent
 * activity drives the JVM until Ctrl+C, when the shutdown hook tears JADE down
 * cleanly. (Task 01's smoke test moved to the test source set — it is no longer a
 * production entry point.)
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        BootstrapConfig cfg = BootstrapConfig.fromSystemProperties();
        JadeBootstrap boot = new JadeBootstrap();
        Runtime.getRuntime().addShutdownHook(new Thread(boot::shutdown, "JadeShutdownHook"));
        boot.start(cfg);
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                boot.getLLMBridge(), boot.getEventBus());
        // Block forever; the shutdown hook handles teardown on Ctrl+C / SIGTERM.
        Thread.currentThread().join();
    }
}
