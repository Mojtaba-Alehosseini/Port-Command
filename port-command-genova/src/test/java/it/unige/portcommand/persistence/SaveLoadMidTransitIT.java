package it.unige.portcommand.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.agents.MessageProbe;
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
        // saving"). The contracted grant clears in well under a sim minute; the walk-in does NOT —
        // nothing here answers it, so it blocks until its patience runs out. See
        // WALK_IN_PATIENCE_HEADROOM_MS for the arithmetic and why the old 15 s budget was wrong.
        Path saved = saveNowRetrying(session, WALK_IN_PATIENCE_HEADROOM_MS);
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
        Path dockedSave = saveNowRetrying(session, WALK_IN_PATIENCE_HEADROOM_MS);
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

    /**
     * S5 (ADR-16, D3): the berth wait queue is PERSISTENCE-FIRST. A walk-in whose deal closed
     * while its only compatible berth was held used to sit in grant-limbo with
     * {@code flowPhase()==null} — refusing every manual save and silently DROPPED by the EOD
     * autosave with its fee already booked. This drives the full story: queue → save (allowed,
     * stage and phase both {@code AWAITING_BERTH} by name) → load (the queue re-forms from the
     * persisted stage) → the blocker departs → the sweep grants the RESTORED vessel and the
     * {@code restoreFrom} arm's {@code AwaitBerthBehaviour} carries it into transit.
     *
     * <p>The departure is injected (a probe INFORM on the real {@code departed} intent) — the
     * blocker's own multi-sim-hour service timeline is the docking round-trip's concern above,
     * and the release path itself is pinned wire-level by {@code HarbourMasterAgentIT}'s queue
     * cases. The tanker fixture (11.0/200/60000 → berth_1 ONLY, R8) is what makes the queue
     * deterministic.
     */
    @Test
    void queuedWalkIn_awaitingBerth_survivesSaveLoad_andIsGrantedAfterRestore() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        SimClock clock = boot.getSimClock();

        // --- Stage: C001 (CONTRACT-1 → berth_1) announces at once and takes the berth. ---
        it.unige.portcommand.ontology.VesselSpec c001 = new it.unige.portcommand.ontology.VesselSpec(
                "C001", "cargo_vessel", 9.5, 180.0, 40_000, "general_cargo", 0L);
        it.unige.portcommand.bootstrap.AgentRoster.spawnContractedVessel(
                boot.getSpawner(), c001, "CONTRACT-1", clock, boot.getMarketHistoryArtifact(),
                session.directory());
        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("C001");
            return t != null && "berth_1".equals(t.assignedBerth());
        }, 15_000), "fixture: C001 must hold berth_1");

        // --- A tanker walk-in deals through the REAL wire path and must QUEUE. ---
        it.unige.portcommand.ontology.VesselSpec tanker = new it.unige.portcommand.ontology.VesselSpec(
                "WT5", "tanker", 11.0, 200.0, 60_000, "crude_oil", 0L);
        it.unige.portcommand.bootstrap.AgentRoster.spawnWalkIn(
                boot.getSpawner(), tanker, clock, boot.getMarketHistoryArtifact(),
                boot.getRandomSource(),
                new it.unige.portcommand.negotiation.RealNegotiationEngine(
                        it.unige.portcommand.core.Settings.load().roundLimit(),
                        boot.getRandomSource().forStream("nego-WT5")),
                session.directory());
        assertNotNull(awaitEvent(it.unige.portcommand.gui.events.NegotiationOpenedEvent.class,
                e -> "nego-WT5".equals(e.dialogueId()), 15_000), "the walk-in must open its dialogue");
        boot.getEventBus().publish(new it.unige.portcommand.gui.events.PlayerCommandEvent(
                it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind.ACCEPT,
                "WT5", java.util.Map.of()));
        assertNotNull(awaitEvent(it.unige.portcommand.gui.events.DealClosedEvent.class,
                e -> "WT5".equals(e.deal().vesselId()), 15_000), "accepting must close the deal");
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("WT5");
            return t != null && t.stage()
                    == it.unige.portcommand.harbourmaster.VesselTracking.Stage.AWAITING_BERTH;
        }, 10_000), "with berth_1 held, the deal must queue — not grant, refuse, or double-book");

        // --- D3's point: the queued vessel is SAVEABLE and persisted, not limbo. ---
        Path saved = saveNowRetrying(session, WALK_IN_PATIENCE_HEADROOM_MS);
        VesselStateDTO dto = new SaveLoadManager(tempDir).load(saved).agents().activeVessels().stream()
                .filter(v -> "WT5".equals(v.id())).findFirst().orElseThrow(
                        () -> new AssertionError("the queued walk-in must be IN the save, not dropped"));
        assertEquals("AWAITING_BERTH", dto.stage(), "stage persists by NAME");
        assertEquals("AWAITING_BERTH", dto.currentPhase(),
                "the vessel-side phase matches the stage (checkpoint-#6 F1 discipline)");

        // --- Load: RestoreDispatch re-forms the queue from the persisted stage. ---
        session.loadGame(saved);
        HarbourMasterAgent hm2 = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();
        // Observed through activeVessels (a ConcurrentHashMap), NOT berthWaitQueue — the queue
        // is a plain LinkedHashMap confined to the HM thread by design, and the restore sweep
        // is concurrently writing it (S5 adversarial finding 5). Queue membership is derivable
        // from the stage (the queue's own javadoc), so the stage IS the assertion.
        assertTrue(waitUntil(() -> {
            var t = hm2.activeVessels().get("WT5");
            return t != null && t.stage()
                    == it.unige.portcommand.harbourmaster.VesselTracking.Stage.AWAITING_BERTH;
        }, 10_000), "the restored world must carry the vessel at AWAITING_BERTH — the granted "
                + "release below is what proves the queue itself re-formed");

        // --- The blocker departs; the sweep grants the restored vessel. ---
        boot.getSpawner().spawn("dep_probe", MessageProbe.class, new Object[] {
                "harbour_master", jade.lang.acl.ACLMessage.INFORM,
                it.unige.portcommand.core.TerminalJson.write(
                        java.util.Map.of("intent", "departed", "vessel_id", "C001")), null});
        assertTrue(waitUntil(() -> {
            var t = hm2.activeVessels().get("WT5");
            return t != null && "berth_1".equals(t.assignedBerth()) && t.stage().ordinal()
                    >= it.unige.portcommand.harbourmaster.VesselTracking.Stage.AWAITING_TUG.ordinal();
        }, 15_000), "the freed berth must go to the restored queued vessel");
        assertTrue(waitUntil(() -> session.directory().byName("vessel_wt5")
                        .filter(it.unige.portcommand.agents.BaseVesselAgent.class::isInstance)
                        .map(a -> ((it.unige.portcommand.agents.BaseVesselAgent) a).flowPhase()
                                != it.unige.portcommand.agents.BaseVesselAgent.FlowPhase.AWAITING_BERTH)
                        .orElse(false), 15_000),
                "the RESTORED AwaitBerthBehaviour (the restoreFrom arm) must consume the grant");
    }

    /**
     * Bounded retry past a transient {@link SaveNotAllowedException}. Two background streams can
     * refuse a manual save at any instant: a task-23 daily contracted arrival mid-grant, and a
     * task-24 Poisson walk-in holding an open negotiation.
     *
     * <p><b>Budget corrected 2026-08-10.</b> This said both refusals "resolve themselves within a
     * sim minute (well under a wall second)" and allowed 15 s. That is true of the contracted
     * grant and FALSE of the walk-in: nothing in this test answers a Poisson walk-in, so its
     * negotiation stays open for its full patience — 140-480 sim-MINUTES per
     * {@code vessel_templates.json}, which at this test's 300 s/game-day is 29-100 wall-seconds,
     * six times the old budget at the low end. The test passed only while no walk-in happened to
     * spawn in the window; once one did it failed on a clean tree as readily as on a modified one
     * (reproduced both ways, 2026-08-10). {@link #WALK_IN_PATIENCE_HEADROOM_MS} covers the top of
     * that range with margin. The wait stays bounded with an explicit deadline (design rule 5).
     */
    /** Long enough to outlast an unanswered walk-in's full patience at this test's pacing:
     * the widest template range is 480 sim-minutes, which is 100 wall-seconds at 300 s/game-day.
     * Only ever waited out when a Poisson walk-in actually spawns into the save window. */
    private static final long WALK_IN_PATIENCE_HEADROOM_MS = 150_000L;

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
