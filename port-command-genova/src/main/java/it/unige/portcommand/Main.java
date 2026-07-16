package it.unige.portcommand;

import java.util.Arrays;

import javax.swing.SwingUtilities;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.MainWindow;

/**
 * Application entry point. Boots the JADE Main Container and blocks; agent
 * activity drives the JVM until Ctrl+C, when the shutdown hook tears JADE down
 * cleanly. (Task 01's smoke test moved to the test source set — it is no longer a
 * production entry point.) Pass {@code --gui} to also open the Swing shell
 * (task 17); omitted, {@code gradlew run} stays headless exactly as before.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        boolean guiRequested = Arrays.asList(args).contains("--gui");

        BootstrapConfig cfg = BootstrapConfig.fromSystemProperties();
        JadeBootstrap boot = new JadeBootstrap();
        Runtime.getRuntime().addShutdownHook(new Thread(boot::shutdown, "JadeShutdownHook"));
        boot.start(cfg);
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                boot.getLLMBridge(), boot.getEventBus());

        if (guiRequested) {
            SwingUtilities.invokeLater(() -> new MainWindow(boot.getEventBus(), boot.getPortStateArtifact(),
                    boot::shutdown).setVisible(true));
        }

        // Block forever; the shutdown hook handles teardown on Ctrl+C / SIGTERM. The GUI's
        // window-close handler (MainWindow) calls System.exit directly, since this join()
        // would otherwise never return control to let the JVM exit naturally.
        Thread.currentThread().join();
    }
}
