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
import it.unige.portcommand.persistence.dto.VesselStateDTO;
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
        // Retried for the same reason the docked save below is (task 25, 2026-07-27): a live game
        // runs BOTH background streams, and either can transiently refuse a manual save at this
        // instant — a task-23 daily contracted arrival mid-grant, or a task-24 Poisson walk-in with
        // an open, unanswered negotiation ("Finish the current negotiation with WALKIN-1 before
        // saving", seen once in a full-suite run). Both clear on their own in well under a sim
        // minute, so a bounded retry is the deterministic fix; a single-shot save here is a race.
        Path saved = saveNowRetrying(session, 15_000);
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

        // --- Checkpoint-#6 F1 regression (2026-07-18): drive the restored world to the settled
        // docked window — vessel phase DOCKED and the restored tug's escort_complete consumed
        // (HM tracking DOCKED) — and prove a save taken there persists a stage that MATCHES the
        // vessel's phase. Pre-fix the tracking never advanced, so this save carried
        // stage IN_TRANSIT against currentPhase DOCKED (the W35 lie in the day-5 autosave).
        // Budget: ≤6.5 sim-hours in 15-min steps. The vessel docks in ~2 sim-min, but the
        // ESCORT is real distance at real speed: worst tug route ≈ base(50,100) →
        // pickup(700,200) → berth_1(400,150) ≈ 96 km ≈ 5.2 sim-h at 10 kn (TugMath 10 px/km),
        // and the 8 h service window comfortably contains it — a 4 h budget did NOT (the
        // first full-suite run failed exactly there with tug_3/tug_4 still en route).
        boolean settledDocked = false;
        for (int i = 0; i < 26 && !settledDocked; i++) {
            clock.advance(clock.simSecondsToWallMs(900L));
            settledDocked = waitUntil(() -> {
                boolean phaseDocked = session.directory().byName("vessel_c001")
                        .filter(it.unige.portcommand.agents.BaseVesselAgent.class::isInstance)
                        .map(a -> ((it.unige.portcommand.agents.BaseVesselAgent) a).flowPhase()
                                == it.unige.portcommand.agents.BaseVesselAgent.FlowPhase.DOCKED)
                        .orElse(false);
                it.unige.portcommand.harbourmaster.VesselTracking tracking =
                        hm.activeVessels().get("C001");
                return phaseDocked && tracking != null
                        && tracking.stage() == it.unige.portcommand.harbourmaster.VesselTracking.Stage.DOCKED;
            }, 500);
        }
        assertTrue(settledDocked, "the restored vessel must dock AND the restored tug's "
                + "escort_complete must advance HM tracking to DOCKED — vessel phase: "
                + session.directory().byName("vessel_c001")
                        .filter(it.unige.portcommand.agents.BaseVesselAgent.class::isInstance)
                        .map(a -> String.valueOf(((it.unige.portcommand.agents.BaseVesselAgent) a).flowPhase()))
                        .orElse("agent absent")
                + ", tracking: " + hm.activeVessels().get("C001"));
        // Task 23: the daily contracted stream is live background traffic now — a D1 arrival
        // can be mid-grant at this instant, transiently refusing the manual save. Retry.
        Path dockedSave = saveNowRetrying(session, 15_000);
        VesselStateDTO dockedDto = new SaveLoadManager(tempDir).load(dockedSave)
                .agents().activeVessels().stream()
                .filter(v -> "C001".equals(v.id()))
                .findFirst().orElseThrow();
        assertEquals("DOCKED", dockedDto.currentPhase(), "settled-window sanity: vessel-side phase");
        assertEquals(dockedDto.currentPhase(), dockedDto.stage(),
                "the persisted stage must match the vessel's phase at save time (checkpoint-#6 F1)");

        // --- Resume: remaining service (8 h from docking) + departure (1 min). ---
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
        // Task 23 update (2026-07-18): the loaded world now runs the daily contracted stream
        // (C001-D1, F001-D1 …), so the GLOBAL balance legitimately moves post-load. The
        // invariant this test owns is C001-scoped: completing the RESTORED flow moves no
        // further C001 money (fee at grant, tug at award — both in the saved balance), and
        // the balance reconciles exactly to walletAtSave + the recorded background flows.
        long savedSimMillis = savedState.simClock().simMillis();
        List<it.unige.portcommand.harbourmaster.IncomeEvent> postSaveIncome =
                hm.walletLedger().incomeHistory().stream()
                        .filter(e -> e.simTime() > savedSimMillis).toList();
        List<ExpenseEvent> postSaveExpense = hm.walletLedger().expenseHistory().stream()
                .filter(e -> e.simTime() > savedSimMillis).toList();
        assertTrue(postSaveIncome.stream().noneMatch(e -> "C001".equals(e.vesselId()))
                        && postSaveExpense.stream().noneMatch(e -> "C001".equals(e.vesselId())),
                "completing the restored flow moves no further C001 money (fee at grant, tug at "
                        + "award — both are in the saved balance already)");
        double expectedBalance = walletAtSave
                + postSaveIncome.stream().mapToDouble(
                        it.unige.portcommand.harbourmaster.IncomeEvent::amount).sum()
                - postSaveExpense.stream().mapToDouble(ExpenseEvent::amount).sum();
        assertEquals(expectedBalance, hm.walletLedger().balance(), 0.0001,
                "the balance reconciles to the saved wallet plus ONLY the recorded background "
                        + "flows (task 23's daily contracted stream) — nothing moved unrecorded");

        // Checkpoint-#6 F6 (fixed 2026-07-18): tug bids are rounded to whole cents at bid
        // construction, so REAL awarded charges — and the wallet they debited — carry no
        // sub-cent residue (pre-fix the fare+fuel float product left the balance with a
        // permanent …532.615951895947-style tail).
        for (ExpenseEvent charge : tugCharges) {
            assertEquals(Math.round(charge.amount() * 100.0), charge.amount() * 100.0, 1e-6,
                    "a real tug charge must be a whole-cent amount, got " + charge.amount());
        }
        assertEquals(Math.round(hm.walletLedger().balance() * 100.0),
                hm.walletLedger().balance() * 100.0, 1e-6,
                "the wallet holds no sub-cent residue after real CNP awards, got "
                        + hm.walletLedger().balance());
    }

    /** Bounded retry past a transient {@link SaveNotAllowedException}. Two background streams can
     * refuse a manual save at any instant: a task-23 daily contracted arrival mid-grant, and a
     * task-24 Poisson walk-in holding an open negotiation. Both resolve themselves within a sim
     * minute (well under a wall second at the test's 300 s/game-day rate), so the refusal is
     * transient by construction — but a single-shot save is still a race, which is why both saves
     * in this test go through here. */
    private Path saveNowRetrying(GameSession session, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                return session.saveNow();
            } catch (SaveNotAllowedException transientRefusal) {
                if (System.currentTimeMillis() >= deadline) {
                    throw transientRefusal;
                }
                Thread.sleep(100);
            }
        }
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
