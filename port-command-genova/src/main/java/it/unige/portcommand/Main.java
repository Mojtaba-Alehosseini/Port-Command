package it.unige.portcommand;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.swing.SwingUtilities;

import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.MainWindow;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;

/**
 * Application entry point. Builds the {@link GameSession} (which owns the JADE boot, the
 * fleet, the game lifecycle and — task 22 — save/load), starts a fresh run or loads a
 * save, and blocks; the shutdown hook tears JADE down cleanly on Ctrl+C.
 *
 * <p>Flags: {@code --gui} opens the Swing shell (task 17); {@code --day-length
 * <seconds>} overrides how many real seconds one game day lasts (task 24 — maps to the
 * {@code simclock.real.seconds.per.day} system property that
 * {@link BootstrapConfig#fromSystemProperties()} reads; default 300); {@code --load
 * <path>} (task 22) boots FROM a save file instead of starting fresh.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        List<String> argList = Arrays.asList(args);
        boolean guiRequested = argList.contains("--gui");
        applyDayLengthOverride(argList);
        Path loadPath = loadPathArg(argList);

        BootstrapConfig cfg = BootstrapConfig.fromSystemProperties();
        JadeBootstrap boot = new JadeBootstrap();
        Runtime.getRuntime().addShutdownHook(new Thread(boot::shutdown, "JadeShutdownHook"));

        // Task 24 wiring, now session-owned: one shared Leaderboard instance backs the
        // guard's game-over upsert and the GUI dialogs (two instances would read/write the
        // same scores.json with diverging in-memory views).
        Leaderboard leaderboard = new Leaderboard();
        GameSession session = new GameSession(boot, cfg, new SaveLoadManager(), leaderboard);
        if (loadPath != null) {
            session.loadGame(loadPath); // SchemaVersion/CorruptSave surface on the console
        } else {
            // There is no menu screen yet (tasks 21/23) — boot straight into a running game.
            session.startNewGame();
        }

        if (guiRequested) {
            SwingUtilities.invokeLater(() -> new MainWindow(boot.getEventBus(), boot.getPortStateArtifact(),
                    boot.getNlpPipeline()::processChatInput, session.lifecycle(), leaderboard,
                    session, boot::shutdown)
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

    /** {@code --load <path>} → the save file to boot from, or null for a fresh game (task 22). */
    private static Path loadPathArg(List<String> argList) {
        int at = argList.indexOf("--load");
        if (at < 0) {
            return null;
        }
        if (at + 1 >= argList.size()) {
            throw new IllegalArgumentException("--load requires a path (e.g. save/autosave.json)");
        }
        return Path.of(argList.get(at + 1));
    }
}
