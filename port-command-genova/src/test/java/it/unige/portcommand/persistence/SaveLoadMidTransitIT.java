package it.unige.portcommand.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.ContractedFeeEarnedEvent;
import it.unige.portcommand.gui.events.TugJobAwardedEvent;
import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.financial.ExpenseRules;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE mandatory task-22 mid-transit round trip (planning/22 §22.9, sharpened by the
 * session brief): save with a tug en route and the vessel mid-flow, load, let the vessel
 * complete docking and depart — and the tug job is charged EXACTLY once across the whole
 * timeline. Exercises the live load path (teardown → rebuild → resume), which the
 * byte-diff round trip alone cannot.
 *
 * <p>Determinism: the {@code WallClockAdvancer} never runs — the test drives
 * {@code SimClock.advance} (the established IT pattern); waits are bounded polls on the
 * bus audit log ({@code EventBusProbe}, the documented no-Awaitility exception).
 */
@Tag("integration")
class SaveLoadMidTransitIT {

    private static final int TEST_PORT = 18099;

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    @Test
    void saveWithTugEnRoute_load_vesselCompletesDocking_chargedExactlyOnce() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        SimClock clock = boot.getSimClock();

        // --- Stage: a contracted vessel announces, is granted, and its tug CNP awards. ---
        // CONTRACT-1 (data/contracts.json): C001 → berth_1, fee €5200, eta 0 (announces at once).
        it.unige.portcommand.ontology.VesselSpec spec = new it.unige.portcommand.ontology.VesselSpec(
                "C001", "cargo_vessel", 9.5, 180.0, 40_000, "general_cargo", 0L);
        it.unige.portcommand.bootstrap.AgentRoster.spawnContractedVessel(
                boot.getSpawner(), spec, "CONTRACT-1", clock, boot.getMarketHistoryArtifact(),
                session.directory());

        TugJobAwardedEvent award = awaitEvent(TugJobAwardedEvent.class,
                e -> "C001".equals(e.vesselId()), 15_000);
        assertNotNull(award, "the staged contracted flow must award a tug escort");
        assertNotNull(awaitEvent(ContractedFeeEarnedEvent.class,
                e -> "C001".equals(e.vesselId()), 5_000), "the contract fee lands at the grant");

        // Let the award reach the tug and the escort leg start; a little sim time moves it.
        clock.advance(2_000);
        // Bounded poll (the documented no-Awaitility exception): the vessel must have reported
        // a resumable flow phase before a manual save can capture it (grant-limbo refuses).
        assertTrue(waitUntil(() -> session.directory().byName("vessel_c001")
                        .filter(it.unige.portcommand.agents.BaseVesselAgent.class::isInstance)
                        .map(a -> ((it.unige.portcommand.agents.BaseVesselAgent) a).flowPhase() != null)
                        .orElse(false), 10_000),
                "the granted vessel must enter its flow before the save");

        // --- Save mid-transit, then load it back (full teardown + rebuild). ---
        Path saved = session.saveNow();
        GameState savedState = new SaveLoadManager(tempDir).load(saved);
        double walletAtSave = savedState.wallet();
        assertEquals(1, savedState.agents().activeVessels().size(), "C001 is in the save");
        assertTrue(savedState.agents().activeVessels().get(0).assignedTugs().size() >= 1
                        || savedState.agents().tugs().stream().anyMatch(t -> t.job() != null),
                "the escort is persisted mid-flight (tracking tugs or a live tug job)");

        session.loadGame(saved);
        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();
        assertEquals(walletAtSave, hm.walletLedger().balance(), 0.0001,
                "the restored wallet is the saved wallet");

        // --- Resume: transit (≤2 sim-min) + service (8 h) + departure (1 min). ---
        clock.advance(clock.simSecondsToWallMs(10 * 3_600L));
        // Keyed to C001, not isEmpty(): the live Poisson spawner may add walk-ins meanwhile.
        boolean departed = waitUntil(() -> !hm.activeVessels().containsKey("C001"), 20_000);
        assertTrue(departed, "the restored vessel must complete docking + service and depart");

        // --- The heart of the task: each awarded tug charged EXACTLY once, ever. ---
        // A 40,000 t cargo vessel needs 2 tugs (the 07b table) — BOTH awards happened
        // pre-save, so the whole-timeline charge count must equal the pre-save award count,
        // and the load must produce ZERO new awards (a re-award would be a fresh charge).
        List<TugJobAwardedEvent> awards = EventBusProbe.published(boot.getEventBus()).stream()
                .filter(TugJobAwardedEvent.class::isInstance).map(TugJobAwardedEvent.class::cast)
                .filter(e -> "C001".equals(e.vesselId()))
                .toList();
        assertEquals(2, awards.size(),
                "both awards are pre-save (the save happened after the CNP settled); a third "
                        + "would mean the load re-ran the Contract Net for an escorted vessel");
        List<ExpenseEvent> tugCharges = hm.walletLedger().expenseHistory().stream()
                .filter(e -> ExpenseRules.SOURCE_TUG_JOB.equals(e.source()))
                .filter(e -> "C001".equals(e.vesselId()))
                .toList();
        assertEquals(awards.size(), tugCharges.size(),
                "one charge per awarded tug — the resume must never re-award or re-charge");
        assertEquals(awards.stream().mapToDouble(TugJobAwardedEvent::cost).sum(),
                tugCharges.stream().mapToDouble(ExpenseEvent::amount).sum(), 0.0001);
        assertEquals(walletAtSave, hm.walletLedger().balance(), 0.0001,
                "completing the restored flow moves no further money (fee at grant, tug at award "
                        + "— both are in the saved balance already)");
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
