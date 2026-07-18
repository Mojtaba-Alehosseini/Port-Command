package it.unige.portcommand.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.GameLifecycle;
import it.unige.portcommand.lifecycle.GameOverGuard;
import it.unige.portcommand.nlp.PolicyParser;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.persistence.events.GameSavedEvent;
import it.unige.portcommand.util.SimClock;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole-game orchestrator (task 22): owns the boot ↔ world lifecycle that
 * {@code Main} used to inline, plus save and load. One instance per JVM.
 *
 * <p><b>Stable outer shell, replaceable world.</b> {@code JadeBootstrap}'s shared
 * services (EventBus, SimClock, RandomSource, artifacts, NLP stack) and this class's own
 * {@link GameLifecycle}/{@link Leaderboard}/{@link AgentDirectory}/
 * {@link AutosaveCoordinator} survive a load; the JADE container, every agent, and the
 * {@link GameOverGuard} are torn down and rebuilt. Everything that subscribes on the
 * shared bus and dies with the world MUST close on teardown — the agents' takedowns and
 * the guard's {@code close()} do exactly that (the task-20 double-charge trap).
 *
 * <p><b>Load = validate, then teardown, then rebuild.</b> The file is parsed and
 * validated BEFORE anything is torn down, so a corrupt file leaves the running game
 * untouched. A failure after teardown cannot half-boot: the container is shut down again
 * and the lifecycle lands in MENU (LOADING → MENU, the May-audit edge).
 */
public final class GameSession {

    private static final Logger log = LoggerFactory.getLogger(GameSession.class);
    private static final long MANUAL_SAVE_TIMEOUT_SECONDS = 5L;

    private final JadeBootstrap boot;
    private final BootstrapConfig bootConfig;
    private final SaveLoadManager saveLoadManager;
    private final Leaderboard leaderboard;
    private final AgentDirectory directory = new AgentDirectory();
    /** Task 23: the 3 packaged scenarios, loaded+validated eagerly (a bad file fails here). */
    private final it.unige.portcommand.scenario.ScenarioRegistry scenarioRegistry =
            new it.unige.portcommand.scenario.ScenarioRegistry();

    private GameLifecycle lifecycle;
    private AutosaveCoordinator autosaveCoordinator;
    private boolean guiRefreshSubscribed;
    private volatile GameOverGuard gameOverGuard;

    public GameSession(JadeBootstrap boot, BootstrapConfig bootConfig,
                       SaveLoadManager saveLoadManager, Leaderboard leaderboard) {
        this.boot = boot;
        this.bootConfig = bootConfig;
        this.saveLoadManager = saveLoadManager;
        this.leaderboard = leaderboard;
    }

    /** Boots the container + fleet and starts a fresh run (the pre-task-22 Main sequence).
     * Sandbox play — the scenario runtime still rides along (task 23) so the DAILY
     * contracted stream ({@code ContractSchedule}) spawns {@code contracts.json} arrivals
     * from day 1: contracted fees are live income in free play, not just scenarios. */
    public synchronized void startNewGame() {
        boot.start(bootConfig);
        ensureSessionServices();
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                boot.getLLMBridge(), boot.getEventBus(), directory, boot.getPolicyRegistryArtifact(),
                it.unige.portcommand.scenario.ScenarioRuntime.sandbox(java.util.Set.of()));
        this.gameOverGuard = new GameOverGuard(boot.getEventBus(), lifecycle, leaderboard,
                AgentRoster.STARTING_WALLET, AgentRoster.STARTING_REPUTATION);
        lifecycle.startNewGame();
    }

    /**
     * Task 23: boots a scripted scenario. NOT a parallel boot path — the scenario's
     * opening {@link GameState} is built by {@code GameStateBuilder.fromScenario}, run
     * through the SAME invariant gate a save file passes, and the world is rebuilt by the
     * SAME teardown/rebuild body {@link #loadGame} uses (reconciliation dated 2026-07-18;
     * planning/23's §23.10 {@code Main.startScenario} sketch predates task 22). Call OFF
     * the EDT.
     *
     * <p>Seed rule (hard constraint): a null {@code randomSeed} generates a fresh seed —
     * recorded in the built state, hence in every save of the run; {@code 0} is a valid
     * pinned seed, never a sentinel.
     */
    public synchronized void startScenario(String key) throws CorruptSaveException {
        it.unige.portcommand.scenario.Scenario scenario = scenarioRegistry.findByKey(key)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown scenario '" + key + "' — valid keys: "
                                + it.unige.portcommand.scenario.ScenarioRegistry.KEYS));
        long seed = scenario.randomSeed() != null ? scenario.randomSeed()
                : new java.util.Random().nextLong();
        GameState state = GameStateBuilder.fromScenario(scenario, seed,
                resolveDayLength(scenario), Instant.now().toString());
        saveLoadManager.validateState(state, "scenario:" + key); // fail BEFORE teardown (task-22 rule)
        bootWorld(state, new it.unige.portcommand.scenario.ScenarioRuntime(scenario, Set.of()),
                "scenario:" + key);
        boot.getEventBus().publish(
                new it.unige.portcommand.scenario.events.ScenarioStartedEvent(scenario));
        boot.getEventBus().publish(new it.unige.portcommand.gui.events.NotificationEvent(
                "Scenario started: " + scenario.displayName(),
                it.unige.portcommand.gui.events.NotificationEvent.Severity.INFO,
                boot.getSimClock().nowSimMillis()));
        log.info("scenario '{}' started (seed {}, day length {}s)", key, seed,
                state.simClock().realSecondsPerGameDay());
    }

    /**
     * Scenario pacing precedence: an EXPLICIT {@code --day-length} (marker property set by
     * {@code Main}) beats the scenario's {@code settingsOverride.realSecondsPerGameDay}
     * beats the sandbox default — the player's stated intent wins over scenario defaults.
     */
    private long resolveDayLength(it.unige.portcommand.scenario.Scenario scenario) {
        boolean explicitCli = Boolean.getBoolean("simclock.day.length.explicit");
        Long scenarioRate = scenario.settingsOverride() == null ? null
                : scenario.settingsOverride().realSecondsPerGameDay();
        if (!explicitCli && scenarioRate != null) {
            return scenarioRate;
        }
        return bootConfig.realSecondsPerGameDay();
    }

    /**
     * Manual save (Game → Save): quiesce, snapshot ON the HarbourMaster thread, write
     * atomically. Callable from the EDT — the bounded future wait is the planning-file
     * quiesce ("bounded timeout"), and the game is paused for its duration.
     *
     * @throws SaveNotAllowedException while a negotiation is live (Step 22.7)
     */
    public Path saveNow() throws SaveNotAllowedException, IOException {
        boolean pausedHere = lifecycle.pauseIfRunning();
        try {
            GameState state = buildOnHarbourMasterThread();
            Path written = saveLoadManager.save(saveLoadManager.savegamePath(), state);
            boot.getEventBus().publish(new GameSavedEvent(written.toString(),
                    java.nio.file.Files.size(written), false,
                    SimClock.gameDayOf(state.simClock().simMillis())));
            return written;
        } finally {
            if (pausedHere) {
                lifecycle.resumeIfPaused();
            }
        }
    }

    /**
     * Schedules the snapshot as a one-shot behaviour on the HM agent thread — BETWEEN two
     * behaviour steps, which IS the quiesce phase boundary (no HM behaviour can be mid-step
     * while ours runs; the clock is paused so nothing sim-driven moves elsewhere) — and
     * waits, bounded. Never called from the HM thread itself (the autosave path, which IS
     * on that thread, builds inline via {@link AutosaveCoordinator}).
     */
    private GameState buildOnHarbourMasterThread() throws SaveNotAllowedException, IOException {
        HarbourMasterAgent hm = (HarbourMasterAgent) directory.byName("harbour_master")
                .orElseThrow(() -> new IOException("No running game to save (harbour_master absent)"));
        CompletableFuture<GameState> future = new CompletableFuture<>();
        String savedAt = Instant.now().toString();
        GameStateBuilder.Sources sources = sources();
        hm.addBehaviour(new OneShotBehaviour(hm) {
            @Override
            public void action() {
                try {
                    future.complete(GameStateBuilder.build(sources, savedAt, false));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });
        try {
            return future.get(MANUAL_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SaveNotAllowedException notAllowed) {
                throw notAllowed;
            }
            throw new IOException("Snapshot failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Snapshot timed out after " + MANUAL_SAVE_TIMEOUT_SECONDS
                    + "s — the HarbourMaster thread did not reach a phase boundary", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while saving", e);
        }
    }

    /**
     * Full load: validate the file, tear the world down (container kill → every agent's
     * takedown cancels its bus subscriptions; guard + autosave day-guard closed/reset),
     * rebuild through the SAME boot, restore shared state in place, respawn the fleet from
     * the save, and refresh the GUI. Call OFF the EDT (teardown + reboot takes seconds).
     */
    public synchronized void loadGame(Path source)
            throws SchemaVersionException, CorruptSaveException {
        GameState state = saveLoadManager.load(source); // validate FIRST — running game untouched on failure
        bootWorld(state, runtimeFor(state), source.toString());
        log.info("loaded {} — day {}, wallet {}, {} restored vessel(s)", source,
                SimClock.gameDayOf(state.simClock().simMillis()), state.wallet(),
                state.agents().activeVessels().size());
    }

    /**
     * Task 23: the scenario-engine bundle a load re-arms the HarbourMaster with. A save
     * that names a scenario resumes its remaining script (events minus the persisted
     * {@code firedEventIds} — the no-replay guarantee); a sandbox save still carries its
     * fired ids so the daily contracted stream never re-spawns a day's arrivals. The
     * vessel TRACES (restored vessels + the day's income/expense vessel ids) let the
     * engine recover a spawn the save caught pre-grant (adversarial-review finding 1)
     * without ever re-crediting one that already earned.
     */
    private it.unige.portcommand.scenario.ScenarioRuntime runtimeFor(GameState state)
            throws CorruptSaveException {
        java.util.Set<String> fired = new java.util.HashSet<>(state.firedEventIds());
        java.util.Set<String> traces = new java.util.HashSet<>();
        for (var vessel : state.agents().activeVessels()) {
            traces.add(vessel.id());
        }
        for (var income : state.agents().harbourMaster().dayIncome()) {
            if (income.vesselId() != null) {
                traces.add(income.vesselId());
            }
        }
        for (var expense : state.agents().harbourMaster().dayExpense()) {
            if (expense.vesselId() != null) {
                traces.add(expense.vesselId());
            }
        }
        if (state.activeScenario() == null) {
            return it.unige.portcommand.scenario.ScenarioRuntime.sandbox(fired, traces);
        }
        it.unige.portcommand.scenario.Scenario scenario =
                scenarioRegistry.findByKey(state.activeScenario())
                        .orElseThrow(() -> new CorruptSaveException(
                                "save references unknown scenario '" + state.activeScenario() + "'"));
        return new it.unige.portcommand.scenario.ScenarioRuntime(scenario, fired, traces);
    }

    /**
     * The shared teardown → rebuild body behind {@link #loadGame} and
     * {@link #startScenario} (task 22's sequence, unchanged semantics — task 23 only
     * threads the scenario runtime through). The caller has already validated
     * {@code state}, so a failure past this point can only be a boot failure: the catch
     * shuts the half-built world down and lands in MENU.
     */
    private void bootWorld(GameState state, it.unige.portcommand.scenario.ScenarioRuntime runtime,
                           String sourceLabel) {
        boolean wasLive = lifecycle != null && boot.isStarted();
        if (wasLive) {
            lifecycle.beginLoading();
        }
        try {
            if (boot.isStarted()) {
                closeWorld();
                boot.shutdown();
            }
            boot.start(bootConfig);
            ensureSessionServices();
            if (!wasLive) {
                lifecycle.beginLoading(); // cold `--load`/`--scenario` start: MENU → LOADING
            }

            // Shared-service state, restored in place (the GUI holds these instances).
            boot.getRandomSource().setSeed(state.randomSeed());
            boot.getSimClock().restore(state.simClock().simMillis(),
                    state.simClock().realSecondsPerGameDay());
            boot.getMarketHistoryArtifact().restore(state.marketHistory());
            restorePolicies(state.policyTriggers());

            CompletableFuture<HarbourMasterAgent> hmReady = new CompletableFuture<>();
            AgentRoster.respawnFromSave(boot.getSpawner(), boot.getPortStateArtifact(),
                    boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                    boot.getLLMBridge(), boot.getEventBus(), directory,
                    boot.getPolicyRegistryArtifact(), state, GameStateBuilder.reconcileTerminals(state),
                    hmReady, runtime);
            awaitHarbourMasterReady(hmReady);

            GameOverGuard guard = new GameOverGuard(boot.getEventBus(), lifecycle, leaderboard,
                    state.wallet(), state.reputation());
            guard.restoreProgress(SimClock.gameDayOf(state.simClock().simMillis()),
                    state.consecutiveDeepDebtDays(), state.lastDeepDebtDay());
            this.gameOverGuard = guard;
            autosaveCoordinator.resetAfterLoad();

            publishRestoredState(state);
            lifecycle.completeLoading();
        } catch (RuntimeException e) {
            // Never a half-boot: kill whatever came up, land in MENU, surface one error.
            log.error("boot of {} failed after teardown — shutting the half-built world down",
                    sourceLabel, e);
            try {
                closeWorld();
                if (boot.isStarted()) {
                    boot.shutdown();
                }
            } catch (RuntimeException cleanup) {
                log.warn("cleanup after failed load also failed", cleanup);
            }
            // Guarded (adversarial review #1): on a cold `--load`, boot.start() may have thrown
            // BEFORE the lifecycle existed or entered LOADING — an NPE or an illegal MENU→MENU
            // transition here would mask the real cause.
            if (lifecycle != null && lifecycle.mode() == it.unige.portcommand.lifecycle.GameMode.LOADING) {
                lifecycle.failLoading();
            }
            throw e;
        }
    }

    /**
     * Bounded wait for the rebuilt HarbourMaster's setup (the JadeBootstrap.awaitReady
     * latch pattern — no sleeps): its completion guarantees the directory registration and
     * the restored ledgers exist, so an immediate post-load save or IT read cannot race the
     * async spawn (adversarial review #3).
     */
    private void awaitHarbourMasterReady(CompletableFuture<HarbourMasterAgent> hmReady) {
        try {
            hmReady.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("rebuilt HarbourMaster did not finish setup", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting the rebuilt HarbourMaster", e);
        }
    }

    /** The old world's session-scoped subscribers — closed BEFORE the new world spawns. */
    private void closeWorld() {
        if (gameOverGuard != null) {
            gameOverGuard.close();
            gameOverGuard = null;
        }
    }

    /** Lifecycle + autosave subscriber exist once, on first boot (both survive reloads). */
    private void ensureSessionServices() {
        if (lifecycle == null) {
            lifecycle = new GameLifecycle(boot.getSimClock(), boot.getEventBus());
        }
        if (autosaveCoordinator == null) {
            autosaveCoordinator = new AutosaveCoordinator(boot.getEventBus(), saveLoadManager,
                    this::sources);
        }
        if (!guiRefreshSubscribed) {
            // Checkpoint-#6 F3 (fixed 2026-07-18): the ledgers publish only on MUTATION, so a
            // fresh boot showed "Wallet: —" until the first money event — only the LOAD path had
            // a synthetic refresh. The window's one-time GuiReadyEvent (first paint) is the
            // uniform hook: it fires after startNewGame on a fresh `--gui` boot AND after a cold
            // `--load` boot (whose publishRestoredState ran before the window existed and was
            // lost), and this session-scoped subscription is registered before either.
            boot.getEventBus().subscribe(it.unige.portcommand.gui.events.GuiReadyEvent.class,
                    e -> publishLiveHudState("gui_ready"), it.unige.portcommand.util.DeliveryMode.CALLER_THREAD);
            guiRefreshSubscribed = true;
        }
    }

    /** The load-path synthetic refresh (same event shapes), sourced from the LIVE ledgers. */
    private void publishLiveHudState(String source) {
        directory.byName("harbour_master")
                .filter(HarbourMasterAgent.class::isInstance)
                .map(HarbourMasterAgent.class::cast)
                .ifPresent(hm -> {
                    long simMillis = boot.getSimClock().nowSimMillis();
                    boot.getEventBus().publish(new WalletChangedEvent(
                            hm.walletLedger().balance(), 0.0, source, null, simMillis));
                    boot.getEventBus().publish(new ReputationChangedEvent(
                            hm.reputationLedger().score(), 0.0, source, null, simMillis));
                    boot.getEventBus().publish(new SimClockTickEvent(simMillis,
                            SimClock.gameDayOf(simMillis), boot.getSimClock().simHour(),
                            boot.getSimClock().simMinute()));
                });
    }

    /** Re-parses persisted policy triggers straight into the session registry (no bus race). */
    private void restorePolicies(List<String> triggers) {
        boot.getPolicyRegistryArtifact().clear();
        PolicyParser parser = new PolicyParser();
        for (String trigger : triggers == null ? List.<String>of() : triggers) {
            Optional<PolicyRule> rule = parser.parseQuietly(trigger);
            if (rule.isPresent()) {
                boot.getPolicyRegistryArtifact().register(rule.get());
            } else {
                log.warn("persisted policy no longer parses — dropped: '{}'", trigger);
            }
        }
    }

    /**
     * GUI refresh after a load: the loaded-marker event (chat tabs clear on it), then
     * synthetic absolute wallet/reputation/clock events so the HUD shows restored figures
     * immediately — the ledgers publish only on MUTATION, and restoring is not one.
     */
    private void publishRestoredState(GameState state) {
        long simMillis = state.simClock().simMillis();
        boot.getEventBus().publish(new GameLoadedEvent(SimClock.gameDayOf(simMillis),
                state.wallet(), state.reputation()));
        boot.getEventBus().publish(new WalletChangedEvent(state.wallet(), 0.0, "game_loaded",
                null, simMillis));
        boot.getEventBus().publish(new ReputationChangedEvent(state.reputation(), 0.0, "game_loaded",
                null, simMillis));
        boot.getEventBus().publish(new SimClockTickEvent(simMillis, SimClock.gameDayOf(simMillis),
                boot.getSimClock().simHour(), boot.getSimClock().simMinute()));
    }

    // --- accessors for Main / MainWindow / tests ---

    /** Null until the first {@link #startNewGame()} / {@link #loadGame(Path)} boots the shell. */
    public GameLifecycle lifecycle() {
        return lifecycle;
    }

    public Leaderboard leaderboard() {
        return leaderboard;
    }

    public SaveLoadManager saveLoadManager() {
        return saveLoadManager;
    }

    public AgentDirectory directory() {
        return directory;
    }

    public JadeBootstrap boot() {
        return boot;
    }

    public GameOverGuard gameOverGuard() {
        return gameOverGuard;
    }

    /** The 3 packaged scenarios (task 23) — the NewGameDialog's data source. */
    public it.unige.portcommand.scenario.ScenarioRegistry scenarioRegistry() {
        return scenarioRegistry;
    }

    private GameStateBuilder.Sources sources() {
        return new GameStateBuilder.Sources(boot.getSimClock(), boot.getRandomSource(), directory,
                boot.getMarketHistoryArtifact(), boot.getPolicyRegistryArtifact(), gameOverGuard);
    }
}
