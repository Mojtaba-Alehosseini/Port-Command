package it.unige.portcommand.behaviours;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.agents.HmProbeAgent;
import it.unige.portcommand.behaviours.coordination.HandleWeatherAlertBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.lang.acl.ACLMessage;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.gui.events.TugJobAwardedEvent;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.persistence.SaveNotAllowedException;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-01 (S8): a weather-held vessel must genuinely wait out the storm. The HarbourMaster side
 * (CANCEL cascade + {@code weatherHeld} + clear-sweep re-dispatch) has been complete since
 * task 24; the bug was the VESSEL side — the {@code weather_hold} INFORM landed in the
 * catch-all while {@code TransitToBerthBehaviour} ticked to its absolute deadline and docked
 * ~0.33 wall-s after the hold, on every {@code --smoke} run (POST_DEMO_BACKLOG B-01).
 *
 * <p>Weather forcing is TWO-PRONGED, and both prongs are needed. (1) The transition ALERT is
 * hand-crafted and injected through an {@link HmProbeAgent} outbox (the established
 * HarbourMasterAgentIT pattern) because the real broadcast is pause-gated AND its 15-sim-min
 * cadence is 7.5× the whole transit leg — at ANY pacing the periodic can never reliably land
 * inside the transit window (the live storm scenario only works because its scripted
 * WeatherChange forces an immediate transition). (2) The {@link DebugWeatherEvent} cell
 * override keeps the REAL WeatherAgent's subsequent periodics CONSISTENT with the injected
 * state once the clock resumes — without it a benign periodic would trigger the (correct)
 * S8 periodic-release backstop mid-window and the test would measure the release, not the
 * hold. Re-published before jumps because the Markov evolution keeps evolving the cell.
 *
 * <p>Lives in the uncounted parent {@code behaviours} test package (same reason as
 * {@code PoissonSpawnBehaviourIT}: a test class in a catalogue-counted sub-package would
 * shadow {@code BehaviourCatalogueTest}'s scan). Waits are the committed bounded sleep-poll
 * (design rule 5).
 */
@Tag("integration")
class WeatherHoldTransitIT {

    private static final int TEST_PORT = 18099;

    private static final DebugWeatherEvent STORM = new DebugWeatherEvent(32, "poor", 4.0, "stormy");
    private static final DebugWeatherEvent CLEAR = new DebugWeatherEvent(20, "good", 1.0, "sunny");

    @TempDir
    Path tempDir;

    private JadeBootstrap boot;
    private GameSession session;

    @AfterEach
    void tearDown() {
        if (boot != null && boot.isStarted()) {
            boot.shutdown();
        }
    }

    /**
     * The born-red core (S8): hold → the vessel does NOT dock even well past its original
     * transit deadline; clear → a FRESH escort is tendered and the vessel docks. Pre-fix this
     * fails at the not-docked assertion — the vessel docks straight through the hold.
     */
    @Test
    void heldVessel_neverDocksWhileHeld_thenDocksAfterTheClear() throws Exception {
        HarbourMasterAgent hm = bootAndHoldC001MidTransit();
        SimClock clock = boot.getSimClock();

        // --- THE B-01 CLAIM: far past the ORIGINAL 120-sim-s deadline, still not docked. ---
        // Resume lets the production WallClockAdvancer flow sim time again (≈288 sim-s per
        // wall-s at this pacing) and the explicit jump goes well past the original deadline;
        // the 3-s observation window adds ~900 more sim-s on top. A vessel that docks anywhere
        // in that is docking THROUGH the hold.
        boot.getEventBus().publish(STORM); // re-assert before time flows (Markov evolution)
        clock.resume();
        clock.advance(clock.simSecondsToWallMs(600L));
        boolean dockedWhileHeld = waitUntil(() -> vesselPhase() == BaseVesselAgent.FlowPhase.DOCKED, 3_000);
        assertFalse(dockedWhileHeld,
                "B-01: a weather-held vessel must NOT dock while the hold is active — its transit "
                        + "is suspended, not merely its escort. phase=" + vesselPhase()
                        + " tracking=" + hm.activeVessels().get("C001"));
        assertTrue(hm.activeVessels().get("C001").weatherHeld(), "the hold itself must still stand");

        // --- Clear: the EXISTING weather_clear machinery re-tenders a fresh escort. ---
        long awardsBeforeClear = c001AwardCount();
        boot.getEventBus().publish(CLEAR);
        injectAlert(hm, "weather_clear", 20, "good", 1.0, "sunny");
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("C001");
            return t != null && !t.weatherHeld();
        }, 15_000), "the clear alert must release the hold (weather_clear sweep)");
        assertTrue(waitUntil(() -> c001AwardCount() > awardsBeforeClear, 15_000),
                "the resume is a FRESH Contract Net (Beat 5's re-tender), not a resumption");

        // --- The suspended remainder completes: the vessel docks only now. ---
        boot.getEventBus().publish(CLEAR);
        clock.advance(clock.simSecondsToWallMs(600L));
        assertTrue(waitUntil(() -> vesselPhase() == BaseVesselAgent.FlowPhase.DOCKED, 10_000),
                "after the clear the suspended transit must resume and dock — phase=" + vesselPhase());
    }

    /**
     * The persistence half (plan D "save mid-hold, load, still held, then completes"): a held
     * mid-transit vessel round-trips in schema v1 — {@code currentPhase=TRANSIT} with the
     * frozen (sliding) deadline plus the already-persisted {@code weatherHeld} — and the
     * RESTORED vessel stays suspended until a clear reaches the rebuilt world.
     */
    @Test
    void heldVessel_survivesSaveLoad_staysHeld_thenCompletesAfterClear() throws Exception {
        bootAndHoldC001MidTransit();
        SimClock clock = boot.getSimClock();

        // The clock is still PAUSED from staging, which is what makes this save deterministic:
        // Poisson spawns are gated on an unpaused clock (PoissonSpawnBehaviourIT), so no walk-in
        // negotiation can exist to refuse the save; a short bounded retry covers stragglers.
        Path saved = saveNowRetrying(session, 15_000);
        VesselStateDTO dto = new SaveLoadManager(tempDir).load(saved).agents().activeVessels().stream()
                .filter(v -> "C001".equals(v.id())).findFirst().orElseThrow(
                        () -> new AssertionError("the held vessel must be IN the save"));
        assertEquals("TRANSIT", dto.currentPhase(), "a held vessel persists as plain TRANSIT (ADR-17)");
        assertTrue(dto.weatherHeld(), "the hold flag rides the existing DTO field (ADR-17: no new field)");

        session.loadGame(saved);
        // loadGame awaits the rebuilt HarbourMaster's onSetup; the directory entry follows a few
        // statements later (register-last) — the same lookup race, only narrower.
        assertTrue(waitUntil(() -> session.directory().byName("harbour_master").isPresent(), 15_000),
                "fixture: the rebuilt HarbourMaster must register");
        HarbourMasterAgent hm2 = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();
        assertTrue(waitUntil(() -> {
            var t = hm2.activeVessels().get("C001");
            return t != null && t.weatherHeld();
        }, 10_000), "the restored tracking must still be weather-held");
        assertTrue(waitUntil(() -> session.directory().byName("vessel_c001")
                        .filter(BaseVesselAgent.class::isInstance)
                        .map(a -> ((BaseVesselAgent) a).weatherHeld())
                        .orElse(false), 10_000),
                "the RESTORED vessel-side flag must be seeded from the DTO (restoreFrom)");

        // Still suspended in the REBUILT world: jump far past the persisted deadline. The storm
        // is re-asserted FIRST so the rebuilt WeatherAgent's periodic broadcast reinforces the
        // hold — without it, a benign restored/evolved reading would trigger the (correct!)
        // periodic release backstop and this window would measure the release, not the hold.
        boot.getEventBus().publish(STORM);
        clock.resume();
        clock.advance(clock.simSecondsToWallMs(600L));
        boolean dockedWhileHeld = waitUntil(() -> vesselPhase() == BaseVesselAgent.FlowPhase.DOCKED, 3_000);
        assertFalse(dockedWhileHeld, "the RESTORED vessel must stay suspended while held — phase="
                + vesselPhase());

        // Clear reaches the rebuilt world; the restored hold releases and completes.
        boot.getEventBus().publish(CLEAR);
        injectAlert(hm2, "weather_clear", 20, "good", 1.0, "sunny");
        assertTrue(waitUntil(() -> {
            var t = hm2.activeVessels().get("C001");
            return t != null && !t.weatherHeld();
        }, 20_000), "the clear must release the RESTORED hold (transition alert or the periodic "
                + "release backstop — ADR-17)");
        boot.getEventBus().publish(CLEAR);
        clock.advance(clock.simSecondsToWallMs(600L));
        assertTrue(waitUntil(() -> vesselPhase() == BaseVesselAgent.FlowPhase.DOCKED, 15_000),
                "the restored, released vessel must complete its remaining transit — phase="
                        + vesselPhase());
    }

    /**
     * Shared staging: C001 (CONTRACT-1 → berth_1, cargo 40 kt → 2 tugs) announces, is granted,
     * its escort awards (stage IN_TRANSIT + tugs — the hold sweep's filter), then a forced
     * storm broadcast holds it. Returns the HarbourMaster once {@code weatherHeld} is set.
     */
    private HarbourMasterAgent bootAndHoldC001MidTransit() throws Exception {
        boot = new JadeBootstrap();
        session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        // PAUSE DISCIPLINE (found by the born-red run's log): startNewGame runs the production
        // WallClockAdvancer, so sim time flows at ~288 sim-s per wall-s — the 120-sim-s transit
        // would expire ~0.4 wall-s after the grant, LONG before the ~3-wall-s periodic broadcast
        // can deliver the hold (the live storm scenario only catches T901 because its scripted
        // WeatherChange fires a transition alert immediately). Pausing freezes sim time while
        // every message-driven step (announce, grant, CNP, alert delivery) proceeds on wall time
        // — and as a bonus gates Poisson spawns off entirely (canSpawnNow is false paused).
        boot.getSimClock().pause();

        it.unige.portcommand.ontology.VesselSpec spec = new it.unige.portcommand.ontology.VesselSpec(
                "C001", "cargo_vessel", 9.5, 180.0, 40_000, "general_cargo", 0L);
        it.unige.portcommand.bootstrap.AgentRoster.spawnContractedVessel(
                boot.getSpawner(), spec, "CONTRACT-1", boot.getSimClock(),
                boot.getMarketHistoryArtifact(), session.directory());
        // S15 hardening (same race WalkInNamePoolIT lost): startNewGame does not await the
        // HarbourMaster's setup, and directory registration is that setup's last step.
        assertTrue(waitUntil(() -> session.directory().byName("harbour_master").isPresent(), 15_000),
                "fixture: the HarbourMaster must finish booting");
        HarbourMasterAgent hm = (HarbourMasterAgent) session.directory().byName("harbour_master")
                .orElseThrow();

        assertNotNull(awaitEvent(TugJobAwardedEvent.class, e -> "C001".equals(e.vesselId()), 15_000),
                "staging: the escort award flips tracking to IN_TRANSIT with tugs");
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("C001");
            return t != null && !t.assignedTugs().isEmpty();
        }, 10_000), "staging: assigned tugs visible in tracking");

        boot.getEventBus().publish(STORM);
        injectAlert(hm, "weather_threshold", 32, "poor", 4.0, "stormy");
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("C001");
            return t != null && t.weatherHeld();
        }, 15_000), "staging: the injected threshold alert must HOLD the in-transit vessel "
                + "(existing task-24 machinery) — tracking=" + hm.activeVessels().get("C001"));
        // The HM flag flips at SEND time; the vessel's own flag flips when the protocol-tagged
        // INFORM is CONSUMED. Waiting for both before any clock jump is sequencing, not
        // assert-weakening: a jump launched while the INFORM is still in flight would race the
        // 1-sim-s ticker against message delivery — a window that in live play is a few ms wide.
        assertTrue(waitUntil(() -> session.directory().byName("vessel_c001")
                        .filter(BaseVesselAgent.class::isInstance)
                        .map(a -> ((BaseVesselAgent) a).weatherHeld())
                        .orElse(false), 10_000),
                "staging: the vessel-side held flag must be set by the hold INFORM "
                        + "(HandleWeatherHoldBehaviour) before the clock jumps");
        return hm;
    }

    /** One probe per live world (the container teardown on load kills it — respawned lazily). */
    private int probeSerial;

    /**
     * Hand-crafts a protocol-tagged weather alert (the exact shape
     * {@code PeriodicWeatherBroadcastBehaviour} emits, per HarbourMasterAgentIT's builders) and
     * sends it through a fresh {@link HmProbeAgent}. Deterministic and pause-proof — the real
     * broadcast is pause-gated and its cadence can never reliably land inside a transit leg.
     */
    private void injectAlert(HarbourMasterAgent hm, String event, int windKnots, String visibility,
                             double swell, String state) throws Exception {
        ACLMessage alert = MessageFactory.create(ACLMessage.INFORM);
        alert.addReceiver(hm.getAID());
        alert.setContent(TerminalJson.write(java.util.Map.of(
                "event", event, "wind_knots", windKnots, "visibility", visibility,
                "swell", swell, "state", state)));
        if ("weather_threshold".equals(event)) {
            alert.addUserDefinedParameter("priority", "high");
        }
        alert.setProtocol(HandleWeatherAlertBehaviour.WEATHER_ALERT_PROTOCOL);
        BlockingQueue<ACLMessage> outbox = new LinkedBlockingQueue<>();
        outbox.put(alert);
        boot.getSpawner().spawn("weather_probe_" + (++probeSerial), HmProbeAgent.class,
                new Object[] {outbox, new LinkedBlockingQueue<ACLMessage>(), null});
    }

    private BaseVesselAgent.FlowPhase vesselPhase() {
        return session.directory().byName("vessel_c001")
                .filter(BaseVesselAgent.class::isInstance)
                .map(a -> ((BaseVesselAgent) a).flowPhase())
                .orElse(null);
    }

    private long c001AwardCount() {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(TugJobAwardedEvent.class::isInstance).map(TugJobAwardedEvent.class::cast)
                .filter(e -> "C001".equals(e.vesselId()))
                .count();
    }

    /** Bounded save retry. Under the pause discipline no Poisson walk-in can exist to block a
     * save (spawns gate on an unpaused clock), so this only rides out message-timing stragglers. */
    private Path saveNowRetrying(GameSession s, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                return s.saveNow();
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
