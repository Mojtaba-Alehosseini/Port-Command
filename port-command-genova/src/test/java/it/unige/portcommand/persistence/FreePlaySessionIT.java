package it.unige.portcommand.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.gui.events.ContractedFeeEarnedEvent;
import it.unige.portcommand.gui.events.DealClosedEvent;
import it.unige.portcommand.gui.events.GuiReadyEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PlayerCommandEvent;
import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.GameLifecycle;
import it.unige.portcommand.lifecycle.GameMode;
import it.unige.portcommand.lifecycle.events.AutosaveRequestedEvent;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12) — the launch rework's session half, on the REAL boot paths (bounded
 * sleep-poll helpers, the committed {@code ScenarioSessionIT} pattern):
 * <ol>
 *   <li><b>The empty shell:</b> {@code bootShell()} brings up the container and the session
 *       services and NOTHING else — mode MENU, no agent — and is idempotent.</li>
 *   <li><b>Free play from the shell is a live sandbox:</b> {@code startSandbox(seed)} boots the
 *       {@code freshSandbox} state through the load path; the world runs, opens on the sandbox
 *       figures, earns the daily contracted fee, saves as a sandbox save and loads back.</li>
 *   <li><b>Free play REPLACES a running world</b> — the thing {@code startNewGame()} cannot do:
 *       clock, wallet, settings, scenario engine, vessels, berth bookings and bus subscribers all
 *       belong to the new game afterwards.</li>
 *   <li><b>Load works from the shell</b> (Game ▸ Load Game… with no game running), and a refused
 *       file leaves the shell as it was.</li>
 *   <li><b>The port-status re-announce:</b> the HarbourMaster publishes {@code PortStatusEvent}
 *       only on change and the bus has no replay, so the session asks for one after
 *       {@code GameLoadedEvent} and on {@code GuiReadyEvent}. Asserted through the bus only.</li>
 * </ol>
 *
 * <p><b>Pace.</b> The first case needs game time to pass (a contracted arrival, a save), so it
 * runs the suite's usual 5 s day. The others assert on a world that has only just started, and run
 * a 300 s day so that "the clock was reset" and "the wallet is the opening balance" are direct
 * reads with tens of seconds of margin rather than races against the first arrival.
 */
@Tag("integration")
class FreePlaySessionIT {

    private static final int TEST_PORT = 18099;
    /** The tutorial opens on Day 1 at 08:00 — a sandbox opens at 00:00, sim instant 0. */
    private static final long TUTORIAL_START_SIM_MILLIS = 28_800_000L;
    private static final List<String> BERTH_IDS = List.of("berth_1", "berth_2", "berth_3", "berth_4");

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @BeforeEach
    void markDayLengthExplicit() {
        // The ITs pin their own day length and it must beat the tutorial's 1800 s/day pacing
        // override — exactly the --day-length precedence contract (GameSession.resolveDayLength).
        // Free play itself always runs at the boot pace; only the scenario case needs the marker.
        System.setProperty("simclock.day.length.explicit", "true");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("simclock.day.length.explicit");
        // Settings.load() is a JVM-wide volatile and every boot here applies its own block.
        Settings.applyLive(Settings.classpathDefaults());
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void freePlayFromTheShellIsALiveSandbox() throws Exception {
        GameSession session = newSession(5);

        // --- The empty shell: services up, mode MENU, nobody home. ---
        session.bootShell();
        assertTrue(boot.isStarted(), "the shell boots the JADE container");
        GameLifecycle lifecycle = session.lifecycle();
        assertNotNull(lifecycle, "the window's constructor dereferences the lifecycle");
        assertEquals(GameMode.MENU, lifecycle.mode(), "no world yet — the mode stays MENU");
        assertNotNull(boot.getEventBus(), "the shell provides the bus the panels subscribe on");
        assertNotNull(boot.getPortStateArtifact(), "…and the port artifact");
        assertNotNull(boot.getNlpPipeline(), "…and the NLP pipeline the chat captures at launch");
        assertTrue(session.directory().byName("harbour_master").isEmpty(), "no HarbourMaster in the shell");
        assertEquals(0, session.directory().size(), "no agent of any kind in the shell");
        assertNull(session.gameOverGuard(), "no world, so no game-over guard");
        assertNull(session.activeScenarioKey(), "no world, so no scenario");

        session.bootShell(); // idempotent
        assertSame(lifecycle, session.lifecycle(), "a second bootShell keeps the same lifecycle");
        assertEquals(GameMode.MENU, session.lifecycle().mode());
        assertEquals(1, EventBusProbe.subscriberCount(boot.getEventBus(), GuiReadyEvent.class),
                "one GUI-refresh subscription, however often the shell is booted");
        assertEquals(1, EventBusProbe.subscriberCount(boot.getEventBus(), AutosaveRequestedEvent.class),
                "one AutosaveCoordinator, however often the shell is booted");
        assertTrue(EventBusProbe.published(boot.getEventBus()).stream()
                        .noneMatch(GameLoadedEvent.class::isInstance),
                "the shell starts no game");

        // --- Free play from the shell. ---
        session.startSandbox(42L);

        assertEquals(GameMode.RUNNING, session.lifecycle().mode(), "MENU → LOADING → RUNNING");
        assertSame(lifecycle, session.lifecycle(), "the window keeps the lifecycle it was built with");
        assertNull(session.activeScenarioKey(), "free play runs no scenario");
        assertNotNull(session.gameOverGuard(), "the new world has its guard");
        assertEquals(new GameLoadedEvent(1, AgentRoster.STARTING_WALLET, 70), lastLoaded(),
                "Day 1, €50,000, reputation 70 — what the New Game dialog promises");
        HarbourMasterAgent hm = harbourMaster(session);
        assertEquals(AgentRoster.STARTING_REPUTATION, hm.reputationLedger().score(), 0.0001,
                "the HarbourMaster's reputation ledger opens on the sandbox figure");
        assertNotNull(hm.scriptedEvents(),
                "free play still carries the scenario engine — it runs the daily contracted stream");
        assertNull(hm.scriptedEvents().scenarioKey(), "…with no scenario in it");
        assertNotNull(awaitEvent(NotificationEvent.class, e -> "Free play started".equals(e.text()), 5_000),
                "the player is told the game started");

        // The Port table's first rows, through the bus: the re-announce requested AFTER the
        // loaded-marker (the table blanks itself on that marker; at this pace the HarbourMaster's
        // own first tick can land before it).
        PortStatusEvent empty = awaitAfterLastLoad(PortStatusEvent.class, FreePlaySessionIT::allFourFree, 10_000);
        assertNotNull(empty, "a PortStatusEvent with four free berths must follow GameLoadedEvent");
        assertEquals(BERTH_IDS, empty.berths().stream().map(PortStatusEvent.BerthRow::berthId).toList(),
                "always the port's four berths, in berth-id order");
        assertEquals(1, empty.gameDay());

        // --- It is a LIVE sandbox: the daily contracted stream runs from day 1. ---
        ContractedFeeEarnedEvent day1 = awaitEvent(ContractedFeeEarnedEvent.class,
                e -> "CONTRACT-1-D1".equals(e.contractId()), 20_000);
        assertNotNull(day1, "free play started through the load path must still earn contracts.json income");
        assertEquals("C001-D1", day1.vesselId());
        assertEquals(5_200.0, day1.fee());

        // The ledger opened on €50,000: whatever the first money movement of the game is, the
        // balance before it was the starting wallet. (Deterministic — no read of a moving ledger.)
        WalletChangedEvent firstMove = awaitAfterLastLoad(WalletChangedEvent.class, e -> e.delta() != 0.0, 20_000);
        assertNotNull(firstMove, "the contracted fee must reach the wallet");
        assertEquals(AgentRoster.STARTING_WALLET, firstMove.balance() - firstMove.delta(), 0.0001,
                "the sandbox ledger must open on the starting wallet (first movement: " + firstMove + ")");

        // The granted vessel shows up in the Port table as its berth's holder.
        PortStatusEvent held = awaitEvent(PortStatusEvent.class, e -> e.berths().stream()
                .anyMatch(row -> day1.vesselId().equals(row.vesselId())
                        && day1.berthId().equals(row.berthId())), 15_000);
        assertNotNull(held, "the port status must show " + day1.vesselId() + " holding " + day1.berthId());

        // --- A Free play game saves as a SANDBOX save, and loads back. ---
        Path saved = awaitSave(session, 20_000);
        GameState savedState = new SaveLoadManager(tempDir).load(saved);
        assertNull(savedState.activeScenario(), "a free-play save names no scenario");
        double walletAtSave = savedState.wallet();

        session.loadGame(saved);

        assertEquals(GameMode.RUNNING, session.lifecycle().mode());
        assertNull(session.activeScenarioKey(), "a sandbox save loads as free play");
        assertNotSame(hm, harbourMaster(session), "the load rebuilt the world");
        GameLoadedEvent reloaded = lastLoaded();
        assertEquals(walletAtSave, reloaded.wallet(), 0.0001, "the load announces the saved wallet");
        assertEquals(savedState.reputation(), reloaded.reputation());
        assertEquals(SimClock.gameDayOf(savedState.simClock().simMillis()), reloaded.gameDay());
        PortStatusEvent afterLoad = awaitAfterLastLoad(PortStatusEvent.class, e -> true, 10_000);
        assertNotNull(afterLoad, "a load must be followed by a port-status announce — the table "
                + "blanked itself on GameLoadedEvent and nothing else would refill a quiet port");
        assertEquals(BERTH_IDS, afterLoad.berths().stream().map(PortStatusEvent.BerthRow::berthId).toList());
        // …and the rebuilt ledger really holds the saved wallet: the first post-load movement
        // (the end-of-day costs at the latest, a few seconds away at this pace) starts from it.
        WalletChangedEvent firstMoveAfterLoad =
                awaitAfterLastLoad(WalletChangedEvent.class, e -> e.delta() != 0.0, 20_000);
        assertNotNull(firstMoveAfterLoad, "the reloaded world must keep running (no money moved in 20 s)");
        assertEquals(walletAtSave, firstMoveAfterLoad.balance() - firstMoveAfterLoad.delta(), 0.0001,
                "the reloaded ledger must start from the saved wallet (first movement: "
                        + firstMoveAfterLoad + ")");
    }

    @Test
    void freePlayReplacesARunningScenario() throws Exception {
        GameSession session = newSession(300);

        // The window is open on the shell, the player picks the tutorial…
        session.bootShell();
        session.startScenario("tutorial");

        assertEquals(GameMode.RUNNING, session.lifecycle().mode(), "a scenario starts from the shell");
        assertEquals("tutorial", session.activeScenarioKey());
        assertEquals(new GameLoadedEvent(1, 15_000.0, 50), lastLoaded(), "the tutorial's opening figures");
        assertEquals("EASY", Settings.load().difficulty(), "the tutorial's settings override is in force");
        assertTrue(boot.getSimClock().nowSimMillis() >= TUTORIAL_START_SIM_MILLIS, "the tutorial opens at 08:00");
        HarbourMasterAgent tutorialHm = harbourMaster(session);
        assertEquals("tutorial", tutorialHm.scriptedEvents().scenarioKey());

        // …and lets it become visibly non-empty: C501 spawned, granted, booked on berth_1.
        assertNotNull(awaitEvent(ContractedFeeEarnedEvent.class, e -> "C-501".equals(e.contractId()), 20_000),
                "fixture: the tutorial's C-501 must be granted before the world is replaced");
        assertTrue(waitUntil(() -> boot.getPortStateArtifact().getBerth("berth_1")
                        .filter(b -> "C501".equals(b.vesselId())).isPresent(), 15_000),
                "fixture: berth_1 must be booked for C501 in the shared port artifact");
        assertTrue(session.directory().byName("vessel_c501").isPresent(), "fixture: C501's agent is alive");

        // Game ▸ New Game… ▸ Free play.
        session.startSandbox(42L);

        assertEquals(GameMode.RUNNING, session.lifecycle().mode(), "RUNNING → LOADING → RUNNING");
        assertNull(session.activeScenarioKey(), "the scenario is over — this is free play");
        assertEquals(new GameLoadedEvent(1, AgentRoster.STARTING_WALLET, 70), lastLoaded());

        // Clock: back to Day 1 00:00 at the boot pace (the tutorial was past 08:00).
        assertTrue(boot.getSimClock().nowSimMillis() < TUTORIAL_START_SIM_MILLIS,
                "the clock must be reset to the start of day 1, was " + boot.getSimClock().nowSimMillis());
        assertEquals(300L, boot.getSimClock().realSecondsPerGameDay());

        // Settings: the classpath baseline, not the tutorial's EASY.
        assertEquals("NORMAL", Settings.load().difficulty(), "the tutorial's difficulty must not leak");
        assertEquals(300L, Settings.load().realSecondsPerGameDay(), "the settings' pace matches the clock's");
        assertEquals(1, Settings.load().gameSpeed());

        // The HarbourMaster is a new one, on the sandbox figures, with no scenario in its engine.
        HarbourMasterAgent hm = harbourMaster(session);
        assertNotSame(tutorialHm, hm, "the world was rebuilt");
        assertEquals(AgentRoster.STARTING_WALLET, hm.walletLedger().balance(), 0.0001,
                "€50,000 — the tutorial's wallet was €15,000 plus C-501's fee");
        assertEquals(AgentRoster.STARTING_REPUTATION, hm.reputationLedger().score(), 0.0001,
                "reputation 70 — the tutorial's was 50");
        assertNotNull(hm.scriptedEvents(), "the daily contracted stream needs the engine");
        assertNull(hm.scriptedEvents().scenarioKey(), "no tutorial script in the new world");
        assertFalse(hm.activeVessels().containsKey("C501"), "the tutorial's vessel is not tracked any more");

        // Nothing of the old world survives: its vessel agent, its berth booking…
        assertTrue(waitUntil(() -> session.directory().byName("vessel_c501").isEmpty(), 10_000),
                "the tutorial's C501 agent must be gone from the directory");
        assertTrue(waitUntil(() -> boot.getPortStateArtifact().getBerth("berth_1")
                        .filter(b -> b.status() == BerthOccupancy.Status.FREE && b.vesselId() == null)
                        .isPresent(), 10_000),
                "the new terminals must push berth_1 FREE over the tutorial's booking, got "
                        + boot.getPortStateArtifact().getBerth("berth_1"));
        PortStatusEvent empty = awaitAfterLastLoad(PortStatusEvent.class, FreePlaySessionIT::allFourFree, 10_000);
        assertNotNull(empty, "the new world announces an empty port after GameLoadedEvent");
        assertNotNull(awaitAfterLastLoad(NotificationEvent.class,
                        e -> "Free play started".equals(e.text()), 5_000),
                "the player is told the new game started");

        // …and its bus subscribers (the task-20 double-charge trap, on the new boot path). The
        // counts are polled: the Assistant's setup is not awaited by the boot.
        assertSubscribers(1, DealClosedEvent.class, "exactly one LedgerCoordinator alive");
        assertSubscribers(1, PlayerCommandEvent.class, "exactly one DispatchPlayerCommand subscription");
        assertSubscribers(1, NegotiationOpenedEvent.class, "exactly one Assistant autopilot plan");
        assertSubscribers(1, AutosaveRequestedEvent.class, "exactly one AutosaveCoordinator (session-scoped)");
        assertSubscribers(1, GuiReadyEvent.class, "exactly one GUI-refresh subscription (session-scoped)");
    }

    @Test
    void loadWorksFromTheEmptyShell() throws Exception {
        GameSession session = newSession(300);
        Path saved = new SaveLoadManager(tempDir).save(tempDir.resolve("free-play.json"),
                GameStateBuilder.freshSandbox(7L, 300L, "2026-10-12T09:00:00Z"));

        session.bootShell();
        assertEquals(GameMode.MENU, session.lifecycle().mode());

        // A refused file changes nothing: it is validated before anything is touched.
        assertThrows(CorruptSaveException.class, () -> session.loadGame(tempDir.resolve("no-such-save.json")),
                "a missing file is one clean error");
        assertEquals(GameMode.MENU, session.lifecycle().mode(), "the shell is still in MENU");
        assertTrue(boot.isStarted(), "…and still up");

        // Game ▸ Load Game… with no game running.
        session.loadGame(saved);

        assertEquals(GameMode.RUNNING, session.lifecycle().mode(), "MENU → LOADING → RUNNING");
        HarbourMasterAgent hm = harbourMaster(session);
        assertEquals(AgentRoster.STARTING_WALLET, hm.walletLedger().balance(), 0.0001,
                "the HarbourMaster is up on the saved wallet");
        assertEquals(AgentRoster.STARTING_REPUTATION, hm.reputationLedger().score(), 0.0001);
        assertNull(session.activeScenarioKey(), "the file is a sandbox save");
        assertNotNull(session.gameOverGuard());
        assertEquals(new GameLoadedEvent(1, AgentRoster.STARTING_WALLET, 70), lastLoaded());
        assertTrue(waitUntil(() -> session.directory().size() >= 10, 15_000),
                "the whole fixed fleet must come up (1 HM, 2 terminals, 4 tugs, customs, weather, "
                        + "assistant), got " + session.directory().size());
        assertNotNull(awaitAfterLastLoad(PortStatusEvent.class, FreePlaySessionIT::allFourFree, 10_000),
                "the loaded world announces its (empty) port after GameLoadedEvent");
    }

    /**
     * The other half of the re-announce: a world booted BEFORE the window ({@code --scenario},
     * {@code --load}) has long since published its port status by the time the window subscribes.
     * {@code GuiReadyEvent} — the window's first paint — must get it published again. The session
     * is started cold here (no {@code bootShell}), which is that launch order.
     */
    @Test
    void aWindowThatOpensLateGetsThePortStatusReannounced() throws Exception {
        GameSession session = newSession(300);
        session.startSandbox(7L);
        assertEquals(GameMode.RUNNING, session.lifecycle().mode(), "free play also starts on a cold session");
        assertNotNull(awaitAfterLastLoad(PortStatusEvent.class, FreePlaySessionIT::allFourFree, 10_000),
                "fixture: the world's own first announce");

        // The port is quiet (the first arrival is sim-hours away), so the publisher goes silent.
        // Measured in clock ticks, not wall time: five sim-minute ticks behind the last status.
        assertTrue(waitUntil(() -> ticksSinceLastPortStatus() >= 5, 15_000),
                "fixture: an unchanged port must stop publishing PortStatusEvent");
        long before = portStatusCount();

        boot.getEventBus().publish(new GuiReadyEvent());

        assertTrue(waitUntil(() -> portStatusCount() > before, 10_000),
                "GuiReadyEvent must make the HarbourMaster re-announce the port status — nothing "
                        + "changed in the port, so only the request can have published it");
        assertTrue(allFourFree(lastPortStatus()), "the re-announce is the current (empty) port");
        assertNotNull(awaitEvent(WalletChangedEvent.class, e -> "gui_ready".equals(e.source()), 10_000),
                "…alongside the HUD refresh it has always published");
    }

    // ---- fixtures ----

    private GameSession newSession(long realSecondsPerGameDay) {
        boot = new JadeBootstrap();
        return new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", realSecondsPerGameDay),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
    }

    private static HarbourMasterAgent harbourMaster(GameSession session) {
        return (HarbourMasterAgent) session.directory().byName("harbour_master").orElseThrow();
    }

    private static boolean allFourFree(PortStatusEvent status) {
        return status.berths().size() == 4
                && status.berths().stream().allMatch(PortStatusEvent.BerthRow::isFree)
                && status.queued().isEmpty();
    }

    /** Polls the live subscriber count to {@code expected}, then asserts it for the message. */
    private void assertSubscribers(int expected, Class<? extends Event> type, String message)
            throws InterruptedException {
        waitUntil(() -> EventBusProbe.subscriberCount(boot.getEventBus(), type) == expected, 15_000);
        assertEquals(expected, EventBusProbe.subscriberCount(boot.getEventBus(), type), message);
    }

    private long portStatusCount() {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(PortStatusEvent.class::isInstance).count();
    }

    private PortStatusEvent lastPortStatus() {
        List<Event> all = EventBusProbe.published(boot.getEventBus());
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof PortStatusEvent status) {
                return status;
            }
        }
        throw new AssertionError("no PortStatusEvent in the " + all.size() + "-event audit log");
    }

    /** Sim-clock ticks published after the most recent {@code PortStatusEvent}; 0 if there is none. */
    private long ticksSinceLastPortStatus() {
        List<Event> all = EventBusProbe.published(boot.getEventBus());
        long ticks = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            Event event = all.get(i);
            if (event instanceof PortStatusEvent) {
                return ticks;
            }
            if (event instanceof SimClockTickEvent) {
                ticks++;
            }
        }
        return 0;
    }

    // ---- helpers (the committed task-22 IT pattern: bounded polls, explicit timeouts) ----

    /** The most recent {@code GameLoadedEvent} — every world boot through the load path publishes one. */
    private GameLoadedEvent lastLoaded() {
        List<Event> all = EventBusProbe.published(boot.getEventBus());
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof GameLoadedEvent loaded) {
                return loaded;
            }
        }
        throw new AssertionError("no GameLoadedEvent in the " + all.size() + "-event audit log");
    }

    /**
     * The first matching event published AFTER the most recent {@code GameLoadedEvent}, polled up
     * to {@code timeoutMs}; null on timeout. The boundary is re-derived against the SAME snapshot
     * it slices on every poll (the audit log is a bounded drop-oldest ring since audit P1-2, so a
     * cached index would drift). A marker that has aged out simply reads as "not found yet" — the
     * caller's assertion message then says what was expected.
     */
    private <E extends Event> E awaitAfterLastLoad(Class<E> type, Predicate<E> matcher, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            E found = firstAfterLastLoad(type, matcher);
            if (found != null) {
                return found;
            }
            Thread.sleep(50);
        }
        return firstAfterLastLoad(type, matcher);
    }

    private <E extends Event> E firstAfterLastLoad(Class<E> type, Predicate<E> matcher) {
        List<Event> all = EventBusProbe.published(boot.getEventBus());
        int marker = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof GameLoadedEvent) {
                marker = i;
                break;
            }
        }
        if (marker < 0) {
            return null;
        }
        for (int i = marker + 1; i < all.size(); i++) {
            Event event = all.get(i);
            if (type.isInstance(event) && matcher.test(type.cast(event))) {
                return type.cast(event);
            }
        }
        return null;
    }

    /**
     * Bounded retry past a transient {@link SaveNotAllowedException} — {@code ScenarioSessionIT}'s
     * helper, for the same reason (S14, ADR-18): walk-in patience is conversation time, real
     * seconds whatever the game pace, so an unanswered free-play walk-in would refuse every save
     * for minutes. The test plays the player who lets the offer run out: each refusal lets ten
     * minutes pass on the conversation clock — directly, never through {@code SimClock.advance},
     * so game time does not move — and the vessel's own poll then withdraws it.
     */
    private Path awaitSave(GameSession session, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return session.saveNow();
            } catch (SaveNotAllowedException transientRefusal) {
                boot.getSimClock().conversationClock().advance(600_000L);
                Thread.sleep(100); // the withdrawal (or a grant still on the wire) lands in wall-ms
            }
        }
        throw new AssertionError("manual save never became possible within " + timeoutMs + "ms");
    }

    private <E extends Event> E awaitEvent(Class<E> type, Predicate<E> matcher, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Event event : EventBusProbe.published(boot.getEventBus())) {
                if (type.isInstance(event) && matcher.test(type.cast(event))) {
                    return type.cast(event);
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    private boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
