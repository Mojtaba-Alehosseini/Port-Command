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
import it.unige.portcommand.smoke.SmokeRunner;

/**
 * Application entry point. Builds the {@link GameSession} (which owns the JADE boot, the
 * fleet, the game lifecycle and — task 22 — save/load), starts a fresh run or loads a
 * save, and blocks; the shutdown hook tears JADE down cleanly on Ctrl+C.
 *
 * <p>Flags: {@code --gui} opens the Swing shell (task 17); {@code --day-length
 * <seconds>} overrides how many real seconds one game day lasts (task 24 — maps to the
 * {@code simclock.real.seconds.per.day} system property that
 * {@link BootstrapConfig#fromSystemProperties()} reads; default 3600 since S14 — a 60-minute
 * day at ×1, the speed control multiplies it; when passed it also
 * beats any scenario pacing override); {@code --load <path>} (task 22) boots FROM a save
 * file instead of starting fresh; {@code --scenario <key>} (task 23) boots one of the 3
 * scripted scenarios ({@code tutorial | busy_day | storm}) — mutually exclusive with
 * {@code --load}; {@code --smoke} (task 26) runs {@link SmokeRunner#runAll()} headlessly and
 * exits with its failure count instead of starting a session at all.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        List<String> argList = Arrays.asList(args);
        // Task 26: --smoke owns the whole process — it boots and tears down three worlds of its
        // own, so it must run BEFORE any of the normal session wiring below.
        if (argList.contains("--smoke")) {
            System.exit(SmokeRunner.runAll() == 0 ? 0 : 1);
        }
        boolean guiRequested = argList.contains("--gui");
        applyDayLengthOverride(argList);
        Path loadPath = loadPathArg(argList);
        String scenarioKey = scenarioArg(argList);
        if (loadPath != null && scenarioKey != null) {
            throw new IllegalArgumentException("--load and --scenario are mutually exclusive");
        }

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
        } else if (scenarioKey != null) {
            session.startScenario(scenarioKey); // task 23 — headless-testable scenario boot
        } else {
            // Sandbox boot (in-game New Game… opens the scenario dialog; task 23).
            session.startNewGame();
        }

        if (guiRequested) {
            // S8 (D-05 two-step): an explicit implementation, NOT the old 2-arg method ref — the
            // 3-arg form must reach the pipeline's ctxByVesselId overload, or the interface
            // default would silently drop the resolver and the two-step could never fire.
            final it.unige.portcommand.nlp.NLPPipeline nlp = boot.getNlpPipeline();
            it.unige.portcommand.nlp.ChatInputProcessor processor =
                    new it.unige.portcommand.nlp.ChatInputProcessor() {
                        @Override
                        public java.util.concurrent.CompletableFuture<it.unige.portcommand.nlp.PipelineResult>
                                process(String text, it.unige.portcommand.nlp.DialogueCtx ctx) {
                            return nlp.processChatInput(text, ctx);
                        }

                        @Override
                        public java.util.concurrent.CompletableFuture<it.unige.portcommand.nlp.PipelineResult>
                                process(String text, it.unige.portcommand.nlp.DialogueCtx ctx,
                                        java.util.function.Function<String, it.unige.portcommand.nlp.DialogueCtx> ctxByVesselId) {
                            return nlp.processChatInput(text, ctx, ctxByVesselId);
                        }
                    };
            SwingUtilities.invokeLater(() -> new MainWindow(boot.getEventBus(), boot.getPortStateArtifact(),
                    processor, session.lifecycle(), leaderboard,
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
        // Task 23: mark the override EXPLICIT — scenario pacing defers to a player-stated
        // day length (GameSession.resolveDayLength), but must not treat the ambient
        // default as one.
        System.setProperty("simclock.day.length.explicit", "true");
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

    /** {@code --scenario <key>} → the scripted scenario to boot (task 23), or null. */
    private static String scenarioArg(List<String> argList) {
        int at = argList.indexOf("--scenario");
        if (at < 0) {
            return null;
        }
        if (at + 1 >= argList.size()) {
            throw new IllegalArgumentException(
                    "--scenario requires a key: tutorial | busy_day | storm");
        }
        return argList.get(at + 1);
    }
}
