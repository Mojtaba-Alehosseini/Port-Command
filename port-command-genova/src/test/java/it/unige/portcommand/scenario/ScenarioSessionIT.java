package it.unige.portcommand.scenario;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.ContractedFeeEarnedEvent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.GameState;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.persistence.SaveNotAllowedException;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task-23 session capstone, on the REAL boot paths (bounded sleep-poll helpers —
 * the committed task-22 IT pattern; no Awaitility dependency exists):
 * <ol>
 *   <li><b>Scenario boot end-to-end:</b> {@code startScenario("tutorial")} builds the
 *       state through {@code fromScenario}, reboots the world through the task-22 body,
 *       and the scripted wave spawns REAL vessels — the C-501 grant's
 *       {@code ContractedFeeEarnedEvent} is the proof the whole
 *       spawn→announce→Prolog→grant chain ran (REQUEST &gt; 0 by construction).</li>
 *   <li><b>No-replay:</b> a save taken after the wave carries
 *       {@code activeScenario="tutorial"} + the 7 fired ids; loading it re-arms the
 *       engine and the script does NOT re-fire (no second C-501 fee, no resurrected
 *       vessel agent).</li>
 *   <li><b>The sandbox daily contracted stream (the REQUEST:0 headline fix):</b> a plain
 *       {@code startNewGame} at 5 s/day earns CONTRACT-1-D1's fee on day 1 and
 *       CONTRACT-1-D2's on day 2 — contracted fees are live income in free play, every
 *       day, exactly once each.</li>
 * </ol>
 */
@Tag("integration")
class ScenarioSessionIT {

    private static final int TEST_PORT = 18099;

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @BeforeEach
    void markDayLengthExplicit() {
        // The IT pins 5 s/day and must beat the tutorial's 1800 s/day pacing override —
        // exactly the --day-length precedence contract (GameSession.resolveDayLength).
        System.setProperty("simclock.day.length.explicit", "true");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("simclock.day.length.explicit");
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void scenarioBootFiresScriptOnceAndAReloadNeverReplays() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 5),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));

        session.startScenario("tutorial");

        // The scripted contracted spawn ran the REAL chain: spawn → announce REQUEST →
        // Prolog gate → grant → fee. The fee event is the durable witness.
        ContractedFeeEarnedEvent fee = awaitEvent(ContractedFeeEarnedEvent.class,
                e -> "C-501".equals(e.contractId()), 20_000);
        assertNotNull(fee, "the tutorial's C-501 scripted spawn must earn its contracted fee");
        assertEquals("C501", fee.vesselId());

        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();
        assertTrue(waitUntil(() -> !hm.scriptedWaveActive(), 20_000),
                "all 7 tutorial events fire within the compressed IT day");

        // Save after the wave (retrying past transient ARRIVING/NEGOTIATING refusals).
        Path saved = awaitSave(session, 20_000);
        GameState savedState = new SaveLoadManager(tempDir).load(saved);
        assertEquals("tutorial", savedState.activeScenario(), "the save marks the running scenario");
        for (int i = 0; i < 7; i++) {
            assertTrue(savedState.firedEventIds().contains("tutorial#" + i),
                    "fired id tutorial#" + i + " persists (got " + savedState.firedEventIds() + ")");
        }

        // Reload: the engine re-arms from firedEventIds — nothing replays.
        session.loadGame(saved);
        int loadMarker = EventBusProbe.published(boot.getEventBus()).size();
        HarbourMasterAgent hmReloaded = (HarbourMasterAgent) session.directory()
                .byName("harbour_master").orElseThrow();
        assertTrue(!hmReloaded.scriptedWaveActive(),
                "a fully-fired script reloads with no suppression window");
        // Grace window measured in SIM PROGRESS, not wall sleep: wait until the reloaded
        // world has visibly ticked well past every tutorial event time — if the script were
        // going to replay, it would have by then.
        assertTrue(waitUntil(() -> EventBusProbe.published(boot.getEventBus()).stream()
                        .skip(loadMarker)
                        .filter(it.unige.portcommand.gui.events.SimClockTickEvent.class::isInstance)
                        .count() >= 20, 15_000),
                "the reloaded world is running (sim ticks observed)");
        List<Event> postLoad = EventBusProbe.published(boot.getEventBus());
        postLoad = postLoad.subList(loadMarker, postLoad.size());
        assertTrue(postLoad.stream()
                        .filter(ContractedFeeEarnedEvent.class::isInstance)
                        .map(ContractedFeeEarnedEvent.class::cast)
                        .noneMatch(e -> "C-501".equals(e.contractId())),
                "the C-501 scripted spawn must NOT replay after the load");
        assertTrue(postLoad.stream()
                        .filter(CommLogEvent.class::isInstance).map(CommLogEvent.class::cast)
                        .noneMatch(e -> e.performative() == ACLMessage.REQUEST
                                && "vessel_c501".equals(e.sender())),
                "no resurrected C501 announces a berth request post-load");
    }

    @Test
    void sandboxEarnsTheContractedFeesDailyExactlyOnce() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 5),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));

        session.startNewGame(); // plain sandbox — no scenario anywhere

        // Day 1: CONTRACT-1's daily clone spawns at 07:00 sim and earns €5,200 at grant.
        ContractedFeeEarnedEvent day1 = awaitEvent(ContractedFeeEarnedEvent.class,
                e -> "CONTRACT-1-D1".equals(e.contractId()), 20_000);
        assertNotNull(day1, "free play must earn contracts.json income (the REQUEST:0 fix)");
        assertEquals(5_200.0, day1.fee());
        assertEquals("C001-D1", day1.vesselId());

        // Day 2: the schedule RECURS with a fresh day-qualified id — income lands again.
        ContractedFeeEarnedEvent day2 = awaitEvent(ContractedFeeEarnedEvent.class,
                e -> "CONTRACT-1-D2".equals(e.contractId()), 20_000);
        assertNotNull(day2, "daily recurrence: day 2 spawns and earns a fresh clone");

        // Exactly once each: the ledger saw one fee per day-qualified contract id.
        long day1Count = EventBusProbe.published(boot.getEventBus()).stream()
                .filter(ContractedFeeEarnedEvent.class::isInstance)
                .map(ContractedFeeEarnedEvent.class::cast)
                .filter(e -> "CONTRACT-1-D1".equals(e.contractId()))
                .count();
        assertEquals(1, day1Count, "one grant per contract per day — never a duplicate");

        // The wallet actually moved for D1's fee (income path, not just the event).
        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();
        assertTrue(hm.walletLedger().incomeHistory().stream()
                        .anyMatch(e -> "C001-D1".equals(e.vesselId())),
                "the contracted fee is real ledger income, visible to the EOD summary");
    }

    // ---- helpers (the committed task-22 IT pattern: bounded polls, explicit timeouts) ----

    private Path awaitSave(GameSession session, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return session.saveNow();
            } catch (SaveNotAllowedException transientRefusal) {
                Thread.sleep(100); // a walk-in is mid-negotiation/arrival — concludes in wall-ms here
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
