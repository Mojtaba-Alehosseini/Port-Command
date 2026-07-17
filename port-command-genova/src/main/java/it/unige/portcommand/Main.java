package it.unige.portcommand;

import java.util.Arrays;
import java.util.List;

import javax.swing.SwingUtilities;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.MainWindow;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.GameLifecycle;
import it.unige.portcommand.lifecycle.GameOverGuard;

/**
 * Application entry point. Boots the JADE Main Container, spawns the fleet,
 * starts the game lifecycle (task 24 — live sim clock via the wall-clock
 * advancer), and blocks; the shutdown hook tears JADE down cleanly on Ctrl+C.
 *
 * <p>Flags: {@code --gui} opens the Swing shell (task 17); {@code --day-length
 * <seconds>} overrides how many real seconds one game day lasts (task 24 —
 * maps to the {@code simclock.real.seconds.per.day} system property that
 * {@link BootstrapConfig#fromSystemProperties()} reads; default 300).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        List<String> argList = Arrays.asList(args);
        boolean guiRequested = argList.contains("--gui");
        applyDayLengthOverride(argList);

        BootstrapConfig cfg = BootstrapConfig.fromSystemProperties();
        JadeBootstrap boot = new JadeBootstrap();
        Runtime.getRuntime().addShutdownHook(new Thread(boot::shutdown, "JadeShutdownHook"));
        boot.start(cfg);
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                boot.getLLMBridge(), boot.getEventBus());

        // Task 24: the game lifecycle + the single game-over authority. One shared Leaderboard
        // instance backs both the guard's game-over upsert and the GUI dialogs — two instances
        // would read/write the same scores.json with diverging in-memory views.
        GameLifecycle lifecycle = new GameLifecycle(boot.getSimClock(), boot.getEventBus());
        Leaderboard leaderboard = new Leaderboard();
        GameOverGuard guard = new GameOverGuard(boot.getEventBus(), lifecycle, leaderboard,
                AgentRoster.STARTING_WALLET, AgentRoster.STARTING_REPUTATION);
        // There is no menu screen yet (tasks 21/23) — boot straight into a running game, the
        // pre-task-24 behaviour plus a live clock.
        lifecycle.startNewGame();

        if (guiRequested) {
            SwingUtilities.invokeLater(() -> new MainWindow(boot.getEventBus(), boot.getPortStateArtifact(),
                    boot.getNlpPipeline()::processChatInput, lifecycle, leaderboard, boot::shutdown)
                    .setVisible(true));
        }

        // Block forever; the shutdown hook handles teardown on Ctrl+C / SIGTERM. The GUI's
        // window-close handler (MainWindow) calls System.exit directly, since this join()
        // would otherwise never return control to let the JVM exit naturally.
        Thread.currentThread().join();
    }

    /**
     * {@code --day-length <seconds>} → the {@code simclock.real.seconds.per.day} system
     * property, BEFORE {@link BootstrapConfig#fromSystemProperties()} reads it. Values
     * below the Settings range (180–600) are allowed on purpose: integration runs use
     * e.g. 5 s/day to reach midnight quickly (planning/24 §24.8).
     */
    private static void applyDayLengthOverride(List<String> argList) {
        int at = argList.indexOf("--day-length");
        if (at < 0) {
            return;
        }
        if (at + 1 >= argList.size()) {
            throw new IllegalArgumentException("--day-length requires a value (real seconds per game day)");
        }
        long seconds = Long.parseLong(argList.get(at + 1));
        if (seconds <= 0) {
            throw new IllegalArgumentException("--day-length must be > 0, got " + seconds);
        }
        System.setProperty("simclock.real.seconds.per.day", String.valueOf(seconds));
    }
}
