package it.unige.portcommand.scenario;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import it.unige.portcommand.agents.BerthOccupancy;
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
import it.unige.portcommand.persistence.events.GameLoadedEvent;
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
        HarbourMasterAgent hmReloaded = (HarbourMasterAgent) session.directory()
                .byName("harbour_master").orElseThrow();
        assertTrue(!hmReloaded.scriptedWaveActive(),
                "a fully-fired script reloads with no suppression window");
        // Grace window measured in SIM PROGRESS, not wall sleep: wait until the reloaded
        // world has visibly ticked well past every tutorial event time — if the script were
        // going to replay, it would have by then.
        assertTrue(waitUntil(() -> publishedAfterLoad().stream()
                        .filter(it.unige.portcommand.gui.events.SimClockTickEvent.class::isInstance)
                        .count() >= 20, 15_000),
                "the reloaded world is running (sim ticks observed)");
        List<Event> postLoad = publishedAfterLoad();
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

    /**
     * FIND-2 / audit P1-1, end-to-end on the real tutorial: the contracted berth booking must
     * span its whole contracted stay instead of evaporating.
     *
     * <p>Before the fix the berth REQUEST carried the contract's time-of-day OFFSET (300 000)
     * where {@code TerminalState} wanted an absolute instant, so {@code expectedFreeAtSim} came
     * out as 21 900 000 — <em>before</em> the 08:00 boot instant this scenario starts at
     * (28 800 000). {@code ProcessCargoHandlingBehaviour} therefore docked the vessel on its
     * first sim-minute tick and freed the berth on the second: the sub-2-second "berth fill
     * flash" the 2026-08-04 live walk reported.
     *
     * <p>Runs at 20 s/day rather than the 5 s/day the other cases use, so C-501's six contracted
     * hours are ~5 real seconds of observable window against a ~28 ms flash — a 180x margin, so
     * the assertion is about the booking's VALUES, not about winning a race.
     */
    @Test
    void tutorialContractedBerthHoldsItsFullStayInsteadOfFlashing() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 20),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));

        session.startScenario("tutorial");

        assertNotNull(awaitEvent(ContractedFeeEarnedEvent.class, e -> "C-501".equals(e.contractId()), 20_000),
                "the tutorial's C-501 scripted spawn must earn its contracted fee");

        // The grant sends the berth REQUEST; the terminal books it on receipt.
        assertTrue(waitUntil(() -> boot.getPortStateArtifact().getBerth("berth_1")
                        .filter(b -> "C501".equals(b.vesselId())).isPresent(), 15_000),
                "berth_1 must be booked for C501 and still be booked - if this times out the "
                        + "booking already evaporated, which IS the FIND-2 flash");

        BerthOccupancy booking = boot.getPortStateArtifact().getBerth("berth_1").orElseThrow();
        long bootInstant = 28_800_000L; // tutorial: startDay 1, startTimeOfDay 08:00
        assertTrue(booking.dockedAtSim() >= bootInstant,
                "dockedAtSim must be an absolute instant at/after the 08:00 boot, got "
                        + booking.dockedAtSim());
        assertTrue(booking.expectedFreeAtSim() > bootInstant,
                "expectedFreeAtSim must lie in the future, not " + booking.expectedFreeAtSim()
                        + " (before the fix it was 21_900_000, i.e. already expired at boot)");
        // Consistency check, NOT the regression gate: TerminalState derives expectedFreeAtSim as
        // etaSim + hours and stores etaSim as dockedAtSim, so this identity also held pre-fix
        // (300_000 + 21_600_000). The two "> bootInstant" assertions above are what actually fail
        // on the unfixed code. Kept because it documents the contracted-hours contract.
        assertEquals(booking.dockedAtSim() + 6L * 3_600_000L, booking.expectedFreeAtSim(),
                "the booking must span C-501's full 6 contracted hours");
    }

    /**
     * S5 Goal C acceptance, busy_day: ALL THREE scripted walk-ins complete their full flow —
     * deal, berth, dock, service, departure — with a player stand-in that just accepts. Two of
     * them can only get there through the S5 berth wait queue: WALKIN-B2 is a ferry whose ONLY
     * berth (berth_4, R8) is held by CONTRACT-2's contracted ferry, and WALKIN-B3 is a tanker
     * whose ONLY berth (berth_1) is held by CONTRACT-1's cargo vessel — under S3's reverted
     * refuse-on-held BOTH were turned away, and pre-S5 they double-booked (A-02). Departure
     * lines in the comm log are the durable completion witness.
     */
    @Test
    void busyDayWalkIns_allThreeCompleteThroughTheQueue() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 5),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startScenario("busy_day");
        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();

        assertTrue(driveWalkInsToDeparture(hm,
                        List.of("WALKIN-B1", "WALKIN-B2", "WALKIN-B3"), 120_000),
                "every scripted busy_day walk-in must complete its full flow; states: "
                        + walkInStates(hm, List.of("WALKIN-B1", "WALKIN-B2", "WALKIN-B3")));
    }

    /**
     * S5 Goal C acceptance, storm: STORM-W1 spawns DURING the storm window, so the player
     * stand-in's early accepts are refused by the Goal-B weather gate (R15-R19) — the retry
     * after the scripted clear is exactly what a human at the chat would do — and the vessel
     * still completes: deal after clearing, berth, dock, departure. T901's contracted flow
     * (hold → re-dispatch) shares the same day.
     */
    @Test
    void stormWalkIn_completesAfterTheClearing() throws Exception {
        boot = new JadeBootstrap();
        GameSession session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 5),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startScenario("storm");
        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();

        assertTrue(driveWalkInsToDeparture(hm, List.of("STORM-W1"), 120_000),
                "the storm walk-in must complete once the sky clears; state: "
                        + walkInStates(hm, List.of("STORM-W1")));
    }

    /**
     * The player stand-in (S5): keep ACCEPTing every listed walk-in while its dialogue is open
     * — a gate refusal (storm weather) simply retries on the next pass, a queued or granted
     * vessel is left alone — until each one's DEPARTURE line is in the comm log. Bounded
     * (design rule 5); 100 ms cadence keeps the ACCEPT spam trivial at IT pacing.
     */
    private boolean driveWalkInsToDeparture(HarbourMasterAgent hm, List<String> vesselIds,
                                            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allDeparted = true;
            for (String id : vesselIds) {
                if (departedLineExists(id)) {
                    continue;
                }
                allDeparted = false;
                it.unige.portcommand.harbourmaster.VesselTracking t = hm.activeVessels().get(id);
                if (t != null && t.stage()
                        == it.unige.portcommand.harbourmaster.VesselTracking.Stage.NEGOTIATING) {
                    boot.getEventBus().publish(new it.unige.portcommand.gui.events.PlayerCommandEvent(
                            it.unige.portcommand.gui.events.PlayerCommandEvent.PlayerCommandKind.ACCEPT,
                            id, java.util.Map.of()));
                }
            }
            if (allDeparted) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** The durable completion witness: the vessel's own departed INFORM rendered in the log. */
    private boolean departedLineExists(String vesselId) {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(CommLogEvent.class::isInstance).map(CommLogEvent.class::cast)
                .anyMatch(e -> e.performative() == ACLMessage.INFORM
                        && e.paraphrase().contains(vesselId)
                        && e.paraphrase().contains("has departed"));
    }

    /** Diagnostic for the timeout message: where each walk-in actually got stuck. */
    private String walkInStates(HarbourMasterAgent hm, List<String> vesselIds) {
        StringBuilder sb = new StringBuilder();
        for (String id : vesselIds) {
            sb.append(id).append('=');
            if (departedLineExists(id)) {
                sb.append("DEPARTED");
            } else {
                sb.append(hm.activeVessels().get(id));
            }
            sb.append(' ');
        }
        return sb.toString().trim();
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

    /**
     * The audit-log slice published AFTER the most recent load, boundary re-derived against the
     * SAME snapshot it slices.
     *
     * <p>This used to cache {@code published().size()} as an index and {@code skip}/{@code subList}
     * from it. Since audit P1-2 the audit log is a bounded drop-oldest ring, so a cached index
     * silently shifts as older events are evicted — and {@code subList} throws outright once the
     * log has wrapped past it. Re-deriving from the {@code GameLoadedEvent} marker is stable under
     * eviction; if the marker itself has aged out, that is stated rather than guessed at, because
     * either alternative (empty slice, or whole log) would quietly make the no-replay assertions
     * vacuous or falsely red.
     */
    private List<Event> publishedAfterLoad() {
        List<Event> all = EventBusProbe.published(boot.getEventBus());
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof GameLoadedEvent) {
                return all.subList(i + 1, all.size());
            }
        }
        throw new AssertionError("no GameLoadedEvent left in the " + all.size()
                + "-event audit log: the bounded ring evicted the post-load boundary, so this "
                + "test can no longer identify its window (raise EventBus.AUDIT_LOG_CAPACITY or "
                + "collect by subscription instead)");
    }

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
