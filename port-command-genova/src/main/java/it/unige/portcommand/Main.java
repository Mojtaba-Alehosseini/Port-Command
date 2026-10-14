package it.unige.portcommand;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
 * fleet, the game lifecycle and — task 22 — save/load), starts what the flags ask for (see
 * {@link LaunchMode}), and blocks; the shutdown hook tears JADE down cleanly on Ctrl+C.
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
 *
 * <p><b>S15 (2026-10-12): a plain {@code --gui} launch no longer starts a game.</b> It used to
 * boot a sandbox and then open the window on it, so the player's first sight was a game already
 * running that they had not chosen — and choosing one meant finding Game ▸ New Game…. Now the
 * window opens on the empty shell ({@code GameSession.bootShell()}: bus, clock and services up,
 * no agents, mode MENU) and shows the New Game dialog itself: the three scenarios or Free play.
 * Nothing else changed: {@code --scenario} and {@code --load} still boot their world FIRST and
 * open the window on it (the demo launchers rely on that), and without {@code --gui} the default
 * is still a running headless sandbox — there is nobody to ask.
 */
public final class Main {

    private Main() {
    }

    /** What a launch boots before anything else happens, decided from the flags alone (S15). */
    enum LaunchMode {
        /** {@code --load <path>}: boot from the save file, with or without a window. */
        LOAD,
        /** {@code --scenario <key>}: boot the scripted scenario, with or without a window. */
        SCENARIO,
        /** {@code --gui} alone: boot the empty shell; the window opens and offers New Game. */
        WINDOW_THEN_NEW_GAME,
        /** No flags: a running sandbox with no window — the pre-S15 default, kept. */
        HEADLESS_SANDBOX
    }

    /**
     * The launch mode for a combination of flags (S15). A world named on the command line wins:
     * {@code --load} or {@code --scenario} boot that world whether or not a window was asked for.
     * Only when neither is given does {@code --gui} decide — the window then opens first and asks
     * the player, where a headless launch has to start something on its own.
     *
     * <p>Pure, and package-private for {@code MainLaunchModeTest}: the four-way branch in
     * {@link #main} is otherwise reachable only by launching the program.
     *
     * @throws IllegalArgumentException for {@code --load} together with {@code --scenario}
     */
    static LaunchMode launchMode(boolean gui, boolean hasLoad, boolean hasScenario) {
        if (hasLoad && hasScenario) {
            throw new IllegalArgumentException("--load and --scenario are mutually exclusive");
        }
        if (hasLoad) {
            return LaunchMode.LOAD;
        }
        if (hasScenario) {
            return LaunchMode.SCENARIO;
        }
        return gui ? LaunchMode.WINDOW_THEN_NEW_GAME : LaunchMode.HEADLESS_SANDBOX;
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
        // Decided (and --load with --scenario refused) before anything is booted.
        LaunchMode mode = launchMode(guiRequested, loadPath != null, scenarioKey != null);

        BootstrapConfig cfg = BootstrapConfig.fromSystemProperties();
        JadeBootstrap boot = new JadeBootstrap();
        Runtime.getRuntime().addShutdownHook(new Thread(boot::shutdown, "JadeShutdownHook"));

        // Task 24 wiring, now session-owned: one shared Leaderboard instance backs the
        // guard's game-over upsert and the GUI dialogs (two instances would read/write the
        // same scores.json with diverging in-memory views).
        Leaderboard leaderboard = new Leaderboard();
        GameSession session = new GameSession(boot, cfg, new SaveLoadManager(), leaderboard);
        switch (mode) {
            // SchemaVersion/CorruptSave surface on the console.
            case LOAD -> session.loadGame(loadPath);
            // Task 23 — headless-testable scenario boot.
            // S18b (PT2): with a window to show it in, the scenario waits for the player.
            case SCENARIO -> session.startScenario(scenarioKey, guiRequested);
            // S15: no world yet. The shell alone gives the window everything its constructor
            // dereferences (lifecycle, bus, clock, port artifact, NLP pipeline); the player then
            // picks a game in the New Game dialog the window opens with.
            case WINDOW_THEN_NEW_GAME -> session.bootShell();
            // Headless default, unchanged: a running sandbox (nobody to show a dialog to).
            case HEADLESS_SANDBOX -> session.startNewGame();
        }

        if (guiRequested) {
            // S8 (D-05 two-step): an explicit implementation, NOT the old 2-arg method ref — the
            // 3-arg form must reach the pipeline's ctxByVesselId overload, or the interface
            // default would silently drop the resolver and the two-step could never fire.
            // S15: captured only HERE, after the boot above. The pipeline does not exist before
            // JadeBootstrap.start — which every branch has now run, the shell-only one included —
            // and a null captured into the processor would be permanent: the first Send would
            // throw instead of parsing. It is one of the shared services that survive every later
            // world swap, so one capture serves all the games of the session. Fail loudly at
            // launch rather than on that first Send if this ever stops holding.
            final it.unige.portcommand.nlp.NLPPipeline nlp = Objects.requireNonNull(boot.getNlpPipeline(),
                    "the NLP pipeline must exist once the session has booted");
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
            // S15: with no world booted above, the window offers the New Game dialog as it opens.
            boolean offerNewGameOnOpen = mode == LaunchMode.WINDOW_THEN_NEW_GAME;
            SwingUtilities.invokeLater(() -> new MainWindow(boot.getEventBus(), boot.getPortStateArtifact(),
                    processor, session.lifecycle(), leaderboard,
                    session, boot::shutdown, offerNewGameOnOpen)
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
