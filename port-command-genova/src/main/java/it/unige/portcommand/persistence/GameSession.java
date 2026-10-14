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
import it.unige.portcommand.gui.events.PolicyListEvent;
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
 *
 * <p><b>S15 (2026-10-12): the shell can exist without a world.</b> {@link #bootShell()} brings
 * up the stable outer shell alone (mode MENU, no agents), so a {@code --gui} launch opens the
 * window first and lets the player choose; {@link #startSandbox()} is the Free play choice — a
 * sandbox built as a {@link GameState} and booted through the SAME teardown/rebuild body a load
 * and a scenario use, so it works from the empty shell and as a replacement for a running
 * world alike. {@link #startNewGame()} is unchanged and remains the headless default.
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
    /**
     * S15 (2026-10-12): the running world's scenario key — {@code null} in free play and while
     * no world exists. Volatile because it is written on the thread that boots the world (under
     * this session's lock) and read from the EDT, which must never wait on that lock: a boot
     * holds it for seconds.
     */
    private volatile String activeScenarioKey;

    public GameSession(JadeBootstrap boot, BootstrapConfig bootConfig,
                       SaveLoadManager saveLoadManager, Leaderboard leaderboard) {
        this.boot = boot;
        this.bootConfig = bootConfig;
        this.saveLoadManager = saveLoadManager;
        this.leaderboard = leaderboard;
    }

    /**
     * S15 (2026-10-12): boots the EMPTY SHELL and nothing else — the JADE container with
     * {@code JadeBootstrap}'s shared services (bus, clock, artifacts, NLP stack) plus this
     * session's own lifecycle, autosave subscriber and GUI-refresh subscription. No agent is
     * spawned, no world exists, and the lifecycle stays in MENU. It is what a plain
     * {@code --gui} launch calls, so the window can open FIRST and offer the New Game dialog
     * instead of a sandbox having silently started behind it.
     *
     * <p>Afterwards everything the window's constructor dereferences is non-null
     * ({@link #lifecycle()}, the bus, the clock, the port artifact, the NLP pipeline), and any
     * of {@link #startSandbox()}, {@link #startScenario(String)} and {@link #loadGame(Path)}
     * may follow: {@code bootWorld} treats the shell like any other started container — MENU →
     * LOADING is a legal edge, there is no guard to close, and the restart kills a container
     * that holds only the bootstrap probe.
     *
     * <p>Idempotent: a second call, or a call while a world is running, changes nothing. (The
     * {@code isStarted()} test only spares the log {@code JadeBootstrap.start}'s "already
     * started" warning; the bootstrap ignores a repeated start by itself.)
     */
    public synchronized void bootShell() {
        if (!boot.isStarted()) {
            boot.start(bootConfig);
        }
        ensureSessionServices();
    }

    /** Boots the container + fleet and starts a fresh run (the pre-task-22 Main sequence).
     * Sandbox play — the scenario runtime still rides along (task 23) so the DAILY
     * contracted stream ({@code ContractSchedule}) spawns {@code contracts.json} arrivals
     * from day 1: contracted fees are live income in free play, not just scenarios.
     *
     * <p>S15 (2026-10-12): semantics unchanged — this stays the headless default and the
     * boot the session ITs use. It can only START the first world: it tears nothing down
     * (the fleet is spawned into whatever container is up, where a running world already
     * owns every agent name) and it resets neither the clock, the seed nor the market
     * history. The window's Free play choice is {@link #startSandbox()} for that reason. */
    public synchronized void startNewGame() {
        boot.start(bootConfig);
        ensureSessionServices();
        this.activeScenarioKey = null; // S15: a plain sandbox runs no scenario
        // S9: a fresh sandbox game resets the effective settings to the classpath baseline —
        // the world-boundary discipline that keeps a previous load's applied block from
        // leaking into a new game (Settings.load() is the session's live holder now).
        it.unige.portcommand.core.Settings.applyLive(
                it.unige.portcommand.core.Settings.classpathDefaults());
        applySettingsSpeed();
        AgentRoster.spawnSingletonsAndFleet(boot.getSpawner(), boot.getPortStateArtifact(),
                boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                boot.getLLMBridge(), boot.getEventBus(), directory, boot.getPolicyRegistryArtifact(),
                it.unige.portcommand.scenario.ScenarioRuntime.sandbox(java.util.Set.of()),
                bootConfig.autopilotPacing());
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
        startScenario(key, false);
    }

    /**
     * S18b (playtest 2, PT2): as {@link #startScenario(String)}, and with {@code paused} the
     * world is paused the moment it is up - the player first sees the scenario at its start, reads
     * the first card, and presses ▶ (or Esc). The window asks for it; headless runs, the smoke run
     * and the ITs keep the running start they have always had.
     */
    public synchronized void startScenario(String key, boolean paused) throws CorruptSaveException {
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
        if (paused) {
            lifecycle.pauseIfRunning();
        }
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
     * S15 (2026-10-12): starts a <b>Free play</b> game on a fresh random seed — the New Game
     * dialog's fourth choice. Same seed rule as a scenario that pins none: generated here,
     * recorded in the opening state and so in every save of the run.
     */
    public synchronized void startSandbox() throws CorruptSaveException {
        startSandbox(new java.util.Random().nextLong());
    }

    /**
     * S15 (2026-10-12): starts a Free play game on {@code seed} (any {@code long}; tests pin one).
     * Like {@link #startScenario} this is NOT a parallel boot path: the sandbox's opening
     * {@link GameState} comes from {@code GameStateBuilder.freshSandbox}, passes the SAME
     * invariant gate a save file passes, and is booted by the SAME teardown/rebuild body
     * {@link #loadGame} uses. So it works from the empty shell ({@link #bootShell()}, MENU), as a
     * REPLACEMENT for a running, paused or ended game, and on a cold session alike. Call OFF the
     * EDT.
     *
     * <p>What that buys over {@link #startNewGame()}, which stays as it is for the headless
     * default: the previous world is torn down first; the seed is this game's own instead of
     * whatever the shared {@code RandomSource} was left holding (a first world there is always
     * the same fixed boot seed); the clock goes back to Day 1 00:00 at the boot pace; market
     * history and policies are cleared; the terminals push four FREE berths over the previous
     * world's bookings; the GUI gets {@code GameLoadedEvent} and the synthetic wallet /
     * reputation / clock / speed refresh; and the HarbourMaster's setup is awaited. The daily
     * contracted stream runs from day 1 in both.
     *
     * <p>The pace is {@code bootConfig}'s — the sandbox default, or an explicit
     * {@code --day-length}; a previous scenario's pacing override does not carry over.
     */
    public synchronized void startSandbox(long seed) throws CorruptSaveException {
        GameState state = GameStateBuilder.freshSandbox(seed, bootConfig.realSecondsPerGameDay(),
                Instant.now().toString());
        saveLoadManager.validateState(state, "sandbox"); // fail BEFORE teardown (task-22 rule)
        bootWorld(state, it.unige.portcommand.scenario.ScenarioRuntime.sandbox(Set.of()), "sandbox");
        boot.getEventBus().publish(new it.unige.portcommand.gui.events.NotificationEvent(
                "Free play started",
                it.unige.portcommand.gui.events.NotificationEvent.Severity.INFO,
                boot.getSimClock().nowSimMillis()));
        log.info("free play started (seed {}, day length {}s)", seed,
                state.simClock().realSecondsPerGameDay());
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
            // S17b (adversarial review): not in the game minute between a midnight and its
            // settlement. A save taken there carries the new day's clock with the old day still
            // open; a load seeds "last day seen" from that clock, so the old day would never be
            // settled - its costs never booked and, in a scenario, the scenario never ended.
            // The clock is paused here, so the answer cannot change under the check.
            if (boot.getSimClock().midnightPending()) {
                throw new SaveNotAllowedException("The day is just ending — save again in a moment.");
            }
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
     * The shared teardown → rebuild body behind {@link #loadGame},
     * {@link #startScenario} and (S15) {@link #startSandbox(long)} (task 22's sequence,
     * unchanged semantics — task 23 only threads the scenario runtime through). The caller
     * has already validated {@code state}, so a failure past this point can only be a boot
     * failure: the catch shuts the half-built world down and lands in MENU.
     */
    private void bootWorld(GameState state, it.unige.portcommand.scenario.ScenarioRuntime runtime,
                           String sourceLabel) {
        // S15 (2026-10-12): "live" here means "a lifecycle and a started container exist" — which
        // since bootShell() is also the EMPTY SHELL (mode MENU, no world). That case needs no
        // branch of its own: MENU → LOADING is the very edge the cold start takes further down,
        // closeWorld() finds no guard to close, and the shutdown below kills a container holding
        // nothing but the bootstrap probe. A boot that fails from the shell lands back in MENU
        // through the same catch as any other.
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
            // S9 (the fix-campaign's "live Settings revert on load" limitation, closed): the
            // saved settings block becomes the session's EFFECTIVE settings BEFORE any agent
            // boots — engines constructed at respawn read the restored roundLimit, the GUI reads
            // the restored budgets, and the next snapshot re-captures exactly these values.
            // Null-tolerant: a settings-less (pre-S9-shape) save means BOOT DEFAULTS — applied
            // explicitly, so a stray pre-load live value can never leak through the load
            // boundary (SettingsRoundTripIT pins exactly that). Scenario boots ride this same
            // line — their GameState.settings IS the merged scenario override, so storm's
            // autopilotEnabled=false now reaches Settings.load().
            it.unige.portcommand.core.Settings.applyLive(state.settings() != null
                    ? state.settings()
                    : it.unige.portcommand.core.Settings.classpathDefaults());
            // S14: the speed control is one of those settings, and the SimClock instance outlives
            // a world swap (the GUI holds it) — so it is re-paced here, before any agent boots
            // and arms a timer at the previous world's pace.
            // S16 (T7, ADR-20): a scenario's script is written for x1 - its events are a few game
            // minutes apart on purpose - so while scripted events are still to come the speed is
            // x1, whatever the save says. A scenario save taken at x4 used to load at x4.
            if (scriptPending(runtime) && it.unige.portcommand.core.Settings.load().gameSpeed() != 1) {
                log.info("scripted events pending - speed x{} from the save reset to x1",
                        it.unige.portcommand.core.Settings.load().gameSpeed());
                it.unige.portcommand.core.Settings.applyLive(
                        it.unige.portcommand.core.Settings.load().withGameSpeed(1));
            }
            boot.getSimClock().setSpeedMultiplier(it.unige.portcommand.core.Settings.load().gameSpeed());

            CompletableFuture<HarbourMasterAgent> hmReady = new CompletableFuture<>();
            AgentRoster.respawnFromSave(boot.getSpawner(), boot.getPortStateArtifact(),
                    boot.getSimClock(), boot.getRandomSource(), boot.getMarketHistoryArtifact(),
                    boot.getLLMBridge(), boot.getEventBus(), directory,
                    boot.getPolicyRegistryArtifact(), state, GameStateBuilder.reconcileTerminals(state),
                    hmReady, runtime, bootConfig.autopilotPacing());
            HarbourMasterAgent hm = awaitHarbourMasterReady(hmReady);

            GameOverGuard guard = new GameOverGuard(boot.getEventBus(), lifecycle, leaderboard,
                    state.wallet(), state.reputation());
            guard.restoreProgress(SimClock.gameDayOf(state.simClock().simMillis()),
                    state.consecutiveDeepDebtDays(), state.lastDeepDebtDay());
            this.gameOverGuard = guard;
            autosaveCoordinator.resetAfterLoad();

            // S15: the new world's scenario (null = free play), set BEFORE the loaded-marker
            // event so anything reacting to GameLoadedEvent already reads the new world's key.
            this.activeScenarioKey = state.activeScenario();
            publishRestoredState(state, hm);
            lifecycle.completeLoading();
        } catch (RuntimeException e) {
            // Never a half-boot: kill whatever came up, land in MENU, surface one error.
            log.error("boot of {} failed after teardown — shutting the half-built world down",
                    sourceLabel, e);
            this.activeScenarioKey = null; // S15: the old world is gone and no new one came up
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
     * latch pattern — no sleeps): its completion guarantees the restored ledgers exist, so an
     * immediate post-load read of the returned agent cannot race the async spawn (adversarial
     * review #3). It does NOT guarantee the directory entry: since S11 an agent registers as
     * the LAST step of {@code PortCommandAgent.setup()}, a few statements after
     * {@code onSetup()} completes this future — so a by-name lookup right after a load can
     * still miss for an instant (the ITs that do one wait for it).
     *
     * <p>S15 (2026-10-12): returns the agent the future completed with, so the caller can ask
     * THIS world's HarbourMaster to re-announce the port status ({@link #publishRestoredState})
     * without a second, racier lookup by name.
     */
    private HarbourMasterAgent awaitHarbourMasterReady(CompletableFuture<HarbourMasterAgent> hmReady) {
        try {
            return hmReady.get(10, TimeUnit.SECONDS);
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
            // ASYNC, not CALLER_THREAD (2026-08-10, audit P1-14). GuiReadyEvent is published from
            // MainWindow.windowOpened, i.e. ON THE EDT, so a CALLER_THREAD subscriber ran
            // publishLiveHudState's directory.byName + ledger reads on the EDT — the one place GUI
            // code reached into live agent state instead of going through the bus, which is the
            // direction INVARIANTS' "GUI <-> agents only through EventBus" rule exists to prevent.
            // Harmless today (all three reads are lock-free), but a ledger that ever took a lock on
            // read would turn it into an EDT stall behind the HarbourMaster thread.
            // ASYNC is safe here: the handler is a handful of volatile reads plus three publishes,
            // fires exactly once per window open, and returns promptly — the shared 2-thread pool's
            // contract. The three events still reach HUDPanel on the EDT exactly as before; the only
            // consequence is that the seed figures can land a few ms later, and HUDModel assigns
            // absolutes rather than accumulating deltas, so that self-corrects on the next event.
            // S15 (2026-10-12): the handler now also asks the HarbourMaster to re-announce the port
            // status — one flag write, still prompt. In the empty shell (bootShell, no world) it
            // finds no HarbourMaster and publishes nothing; the first game's own boot seeds the GUI.
            boot.getEventBus().subscribe(it.unige.portcommand.gui.events.GuiReadyEvent.class,
                    e -> publishLiveHudState("gui_ready"), it.unige.portcommand.util.DeliveryMode.ASYNC);
            guiRefreshSubscribed = true;
        }
    }

    /**
     * The load-path synthetic refresh (same event shapes), sourced from the LIVE ledgers.
     *
     * <p>Guarded on the ledgers being built (2026-08-10): {@code AgentDirectory.register} runs in
     * {@code PortCommandAgent.setup()} BEFORE {@code onSetup()}, so a HarbourMaster is discoverable
     * by name for a window in which its ledger fields are still null. That was latent while this
     * ran {@code CALLER_THREAD} from the EDT — by then setup had always finished — and became
     * reachable once audit P1-14 moved it onto the ASYNC pool, where it can land inside the
     * window. It surfaced as an NPE swallowed by the bus's subscriber isolation, i.e. a silently
     * missing HUD seed rather than a crash. Skipping is the right response: the HUD keeps task
     * 18's "—" placeholder and self-corrects on the first real money event, which is exactly the
     * documented behaviour for "no figure published yet".
     *
     * <p>S15 (2026-10-12): this is also where the Port table gets its first rows on a world that
     * was booted BEFORE the window existed ({@code --scenario}, {@code --load}, the headless-first
     * orders). The HarbourMaster publishes {@code PortStatusEvent} only when the snapshot changes,
     * and the bus has no replay — so a status announced before the window subscribed is simply
     * gone, and a quiet port would show "—" until the next arrival. {@code requestPortStatus()}
     * makes the next tick publish regardless. Asked LAST, after the three HUD seeds, so nothing
     * about it can cost the HUD its figures; with no HarbourMaster (the empty shell) there is
     * nothing to ask and the table correctly stays blank.
     */
    private void publishLiveHudState(String source) {
        directory.byName("harbour_master")
                .filter(HarbourMasterAgent.class::isInstance)
                .map(HarbourMasterAgent.class::cast)
                .filter(hm -> {
                    boolean ready = hm.walletLedger() != null && hm.reputationLedger() != null;
                    if (!ready) {
                        log.debug("{} refresh skipped: the HarbourMaster is registered but still "
                                + "mid-setup (no ledgers yet)", source);
                    }
                    return ready;
                })
                .ifPresent(hm -> {
                    long simMillis = boot.getSimClock().nowSimMillis();
                    boot.getEventBus().publish(new WalletChangedEvent(
                            hm.walletLedger().balance(), 0.0, source, null, simMillis));
                    boot.getEventBus().publish(new ReputationChangedEvent(
                            hm.reputationLedger().score(), 0.0, source, null, simMillis));
                    boot.getEventBus().publish(new SimClockTickEvent(simMillis,
                            SimClock.gameDayOf(simMillis), boot.getSimClock().simHour(),
                            boot.getSimClock().simMinute()));
                    hm.requestPortStatus(); // S15: re-announce for the window that just opened
                    hm.publishScore(); // S17b: the top bar's score, profit and target
                    publishWeatherAndScript(hm); // S16: the top bar's weather, the speed lock
                    // S18a (ADR-23, N11): the autopilot policies in force. On a cold `--load --gui`
                    // start they were restored and announced before this window existed (the bus has
                    // no replay), and Settings would show "none" over policies deciding offers.
                    boot.getEventBus().publish(new PolicyListEvent(boot.getPolicyRegistryArtifact().all()
                            .stream().map(PolicyRule::description).toList()));
                });
    }

    /**
     * S16 (ADR-20): two facts a window needs from the first instant and that are otherwise
     * published only on change. The weather - the top bar shows it and the Assistant's note
     * reads it, and the weather agent's first broadcast is up to fifteen game minutes away -
     * from the HarbourMaster's reading, which is seeded at boot. And whether scripted scenario
     * events are still to come, which locks the speed control and the day-length slider.
     */
    private void publishWeatherAndScript(HarbourMasterAgent hm) {
        HarbourMasterAgent.WeatherReading w = hm.lastWeather();
        if (w != null) {
            boot.getEventBus().publish(new it.unige.portcommand.gui.events.WeatherChangeEvent(
                    w.windKnots(), w.visibility(), w.swell(), w.state(), false));
        }
        boot.getEventBus().publish(
                new it.unige.portcommand.gui.events.ScriptedWaveEvent(hm.scriptedWaveActive()));
    }

    /** Whether {@code runtime}'s scenario still has scripted events that have not fired. */
    private static boolean scriptPending(it.unige.portcommand.scenario.ScenarioRuntime runtime) {
        if (runtime == null || runtime.scenario() == null) {
            return false;
        }
        for (int i = 0; i < runtime.scenario().events().size(); i++) {
            if (!runtime.firedEventIds().contains(runtime.scenario().key() + "#" + i)) {
                return true;
            }
        }
        return false;
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
     *
     * <p>S15 (2026-10-12): and last, a port-status re-announce from {@code hm}, the rebuilt
     * world's HarbourMaster. The Port table blanks itself on {@code GameLoadedEvent}; the new
     * HarbourMaster's first sim-minute tick — which announces unprompted, having published
     * nothing yet — can land BEFORE that event at fast paces (a 5 s day ticks every 3.5 ms while
     * this thread is still building the guard), and the publisher then stays silent until
     * something changes. Requesting after the marker guarantees one announce the table keeps.
     */
    private void publishRestoredState(GameState state, HarbourMasterAgent hm) {
        long simMillis = state.simClock().simMillis();
        boot.getEventBus().publish(new GameLoadedEvent(SimClock.gameDayOf(simMillis),
                state.wallet(), state.reputation()));
        boot.getEventBus().publish(new WalletChangedEvent(state.wallet(), 0.0, "game_loaded",
                null, simMillis));
        boot.getEventBus().publish(new ReputationChangedEvent(state.reputation(), 0.0, "game_loaded",
                null, simMillis));
        boot.getEventBus().publish(new SimClockTickEvent(simMillis, SimClock.gameDayOf(simMillis),
                boot.getSimClock().simHour(), boot.getSimClock().simMinute()));
        // S14: the HUD toggles and the Game ▸ Speed menu re-select the restored speed.
        boot.getEventBus().publish(new it.unige.portcommand.gui.events.GameSpeedChangedEvent(
                boot.getSimClock().speedMultiplier()));
        // S15: served by the HarbourMaster's next tick, on its own thread — after everything above.
        hm.requestPortStatus();
        publishWeatherAndScript(hm); // S16: after the loaded marker, which resets both displays
        hm.publishScore(); // S17b: the loaded day's score, profit and target
    }

    /**
     * S14: puts the effective settings' game speed in force on the shared clock and tells the
     * speed controls. A fresh sandbox game starts at the classpath default (×1) even if the
     * previous world ran at ×4 — the same world-boundary discipline as the settings themselves.
     */
    private void applySettingsSpeed() {
        int speed = it.unige.portcommand.core.Settings.load().gameSpeed();
        boot.getSimClock().setSpeedMultiplier(speed);
        boot.getEventBus().publish(new it.unige.portcommand.gui.events.GameSpeedChangedEvent(speed));
    }

    // --- accessors for Main / MainWindow / tests ---

    /** Null until the first {@link #bootShell()} / {@link #startNewGame()} /
     * {@link #startSandbox()} / {@link #startScenario(String)} / {@link #loadGame(Path)} boots
     * the shell. */
    public GameLifecycle lifecycle() {
        return lifecycle;
    }

    /**
     * S15 (2026-10-12): the key of the scenario the running world belongs to ({@code "tutorial"},
     * {@code "busy_day"}, {@code "storm"}), or {@code null} when it is free play — a
     * {@link #startSandbox()} / {@link #startNewGame()} game, or a load of a sandbox save — and
     * when no world is up at all (the empty shell, a failed boot). A loaded save reports the
     * scenario it was saved in. The window reads it to decide whether the first-launch tutorial
     * cards are still needed: not when the game that just started IS the tutorial scenario.
     *
     * <p>Lock-free (a volatile read), so the EDT may call it while a boot is in progress; it
     * then still names the outgoing world until the new one is up.
     */
    public String activeScenarioKey() {
        return activeScenarioKey;
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
