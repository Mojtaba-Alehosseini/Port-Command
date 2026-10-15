package it.unige.portcommand.behaviours;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.agents.HmProbeAgent;
import it.unige.portcommand.agents.TugMath;
import it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleWeatherAlertBehaviour;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.BootstrapConfig;
import it.unige.portcommand.bootstrap.JadeBootstrap;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.DebugWeatherEvent;
import it.unige.portcommand.gui.events.TugJobAwardedEvent;
import it.unige.portcommand.harbourmaster.TugBasePositions;
import it.unige.portcommand.harbourmaster.TugProfiles;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.GameSession;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.persistence.SaveNotAllowedException;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import it.unige.portcommand.util.Event;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.SimClock;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A weather-held ship waits out the storm at the channel entry, and a load taken in a storm
 * comes back with the gate closed (S8 B-01, rewritten for S16 — ADR-20, decisions B1, B2, B7).
 *
 * <p><b>What changed in S16.</b> The S8 version of this suite staged "escort awarded ⇒ ship in
 * transit ⇒ the hold freezes its transit deadline". None of the three links holds any more: an
 * award leaves the ship at the channel entry (it moves when its tugs have reached it), so the
 * storm catches a ship that is WAITING, and holding it means it stays there with its tugs sent
 * home — there is no frozen deadline. What the suite still proves is the same promise from the
 * player's side: held means not docking, however much game time passes; the clear brings a
 * FRESH escort; the ship docks only after that; and all of it survives a save and a load.
 *
 * <p><b>Time.</b> The world is a real session at 300 s/day (288 game seconds per wall second)
 * and the clock is paused except inside {@link #jump}, which resumes, advances by an exact
 * amount of game time and pauses again. Movement is sim-time integration (tugs) or a sim-time
 * deadline (the ship), so a jump is enough for both; nothing here sleeps to let time pass.
 * The jumps go well past the first game hour (the first case covers almost two game hours). The
 * weather in force is still the one the test set, because both weather behaviours do nothing
 * while the clock is paused, and it is paused whenever a jump is not in progress: no hourly step
 * is taken and nothing is broadcast behind the test's back.
 *
 * <p>Weather forcing is TWO-PRONGED, as before. (1) The alert is hand-crafted and injected
 * through an {@link HmProbeAgent} outbox, because the real broadcast is pause-gated. (2) The
 * {@link DebugWeatherEvent} keeps the REAL weather agent's cell — and so its saved snapshot and
 * any broadcast it makes once time moves — consistent with the injected state.
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

    /** How long a customs inspection takes ({@code FlagForInspectionBehaviour}; CustomsAgentIT pins it). */
    private static final long INSPECTION_SIM_SECONDS = 2_700L;
    /** How far past the slowest tug's arrival a tug leg's jump goes. */
    private static final long TUG_MARGIN_SIM_SECONDS = 3 * 60L;
    /**
     * The run from the tug base to the channel entry for the SLOWEST tug in the profile table, plus
     * the margin. Since S19 (PT39) each tug has its own speed, and R12 ranks the cheap, slow ones
     * first: tug_1 at 10 kn needs about 35.5 game minutes (the old 12-kn fleet needed 29.6, and a
     * fixed 32 left tug_1 under way - the first S19 gate was red on exactly that).
     */
    private static final long TUGS_REACH_SHIP_SIM_SECONDS = slowestTugRunSimSeconds() + TUG_MARGIN_SIM_SECONDS;
    /** The channel leg to berth 1 is about 14 game minutes; 18 is past that. */
    private static final long CHANNEL_LEG_SIM_SECONDS = 18 * 60L;

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
     * Held → no docking, although more game time passes than tugs and channel leg need together;
     * clear → a FRESH escort is tendered, reaches the ship, and only then does the ship dock.
     */
    @Test
    void heldVessel_neverDocksWhileHeld_thenDocksAfterTheClear() throws Exception {
        HarbourMasterAgent hm = bootAndHoldC001AtTheChannelEntry();

        // --- THE CLAIM: a held ship does not move. Positive control further down: the very
        // same two jumps DO dock the ship once it is released. ---
        jump(TUGS_REACH_SHIP_SIM_SECONDS);
        jump(CHANNEL_LEG_SIM_SECONDS);
        assertFalse(waitUntil(() -> vesselPhase("C001") == BaseVesselAgent.FlowPhase.TRANSIT
                        || vesselPhase("C001") == BaseVesselAgent.FlowPhase.DOCKED, 2_000),
                "a weather-held ship must stay at the channel entry — phase=" + vesselPhase("C001")
                        + " tracking=" + hm.activeVessels().get("C001"));
        VesselTracking held = hm.activeVessels().get("C001");
        assertTrue(held.weatherHeld(), "the hold itself must still stand");
        assertEquals(Stage.AWAITING_TUG, held.stage(), "held = granted, not moving");
        assertTrue(held.assignedTugs().isEmpty(), "its escort was sent home");
        assertEquals(BaseVesselAgent.FlowPhase.AWAITING_CLEARANCE, vesselPhase("C001"));

        // --- Clear: a fresh Contract Net, and the ship is told only once tugs are hired. ---
        long awardsBeforeClear = awardCount("C001");
        boot.getEventBus().publish(CLEAR);
        injectAlert(hm, "weather_clear", 20, "good", 1.0, "sunny");
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("C001");
            return t != null && !t.weatherHeld();
        }, 15_000), "the clear must release the hold");
        assertTrue(waitUntil(() -> awardCount("C001") > awardsBeforeClear, 15_000),
                "the release is a FRESH Contract Net, not a resumption of the cancelled escort");
        assertEquals(Stage.AWAITING_TUG, hm.activeVessels().get("C001").stage(),
                "hired is not arrived: the ship still waits for its tugs to reach it");

        // --- The escort reaches the ship, the ship is sent, and it docks. ---
        awaitEscortUnderWay(hm, "C001");
        jump(TUGS_REACH_SHIP_SIM_SECONDS);
        assertTrue(waitUntil(() -> vesselPhase("C001") == BaseVesselAgent.FlowPhase.TRANSIT, 15_000),
                "with its escort on station the ship is ordered to proceed — phase=" + vesselPhase("C001")
                        + " tracking=" + hm.activeVessels().get("C001"));
        assertEquals(Stage.IN_TRANSIT, hm.activeVessels().get("C001").stage(),
                "IN_TRANSIT now means the ship is really moving");
        jump(CHANNEL_LEG_SIM_SECONDS);
        assertTrue(waitUntil(() -> vesselPhase("C001") == BaseVesselAgent.FlowPhase.DOCKED, 15_000),
                "the channel leg ends at the berth — phase=" + vesselPhase("C001"));
        assertTrue(waitUntil(() -> hm.activeVessels().get("C001").stage() == Stage.DOCKED, 10_000),
                "and the HarbourMaster hears of it");
    }

    /**
     * The persistence half: a ship held at the channel entry round-trips in schema v1 — the new
     * phase name plus the held flag the DTO has always carried — stays held in the rebuilt
     * world, and completes after a clear reaches it.
     */
    @Test
    void heldVessel_survivesSaveLoad_staysHeld_thenCompletesAfterClear() throws Exception {
        bootAndHoldC001AtTheChannelEntry();

        // The clock is PAUSED, which is what makes this save deterministic: Poisson spawns are
        // gated on an unpaused clock, so no walk-in negotiation can exist to refuse the save.
        Path saved = saveNowRetrying(session, 15_000);
        VesselStateDTO dto = new SaveLoadManager(tempDir).load(saved).agents().activeVessels().stream()
                .filter(v -> "C001".equals(v.id())).findFirst().orElseThrow(
                        () -> new AssertionError("the held vessel must be IN the save"));
        assertEquals("AWAITING_CLEARANCE", dto.currentPhase(),
                "a held ship is saved where it is: waiting at the channel entry (S16)");
        assertEquals("AWAITING_TUG", dto.stage());
        assertTrue(dto.weatherHeld(), "the hold flag rides the DTO field it always has");

        session.loadGame(saved);
        HarbourMasterAgent hm2 = awaitHarbourMaster();
        assertTrue(waitUntil(() -> {
            var t = hm2.activeVessels().get("C001");
            return t != null && t.weatherHeld();
        }, 10_000), "the restored tracking must still be weather-held");
        assertTrue(waitUntil(() -> vesselPhase("C001") == BaseVesselAgent.FlowPhase.AWAITING_CLEARANCE,
                10_000), "the restored ship waits at the channel entry again");

        // Still held in the REBUILT world, with no storm re-published by hand: the hold flag
        // comes back from the save and the restore sweep, reading the SAVED weather, keeps it
        // and hires nobody. (This case would also pass on the restored flag alone; the case
        // that pins decision B7 - the gate closed before any weather message - is the next.)
        long awardsAfterLoad = awardCount("C001");
        boot.getSimClock().pause(); // the load resumed it
        jump(TUGS_REACH_SHIP_SIM_SECONDS);
        jump(CHANNEL_LEG_SIM_SECONDS);
        assertFalse(waitUntil(() -> vesselPhase("C001") == BaseVesselAgent.FlowPhase.TRANSIT
                        || vesselPhase("C001") == BaseVesselAgent.FlowPhase.DOCKED, 2_000),
                "the RESTORED ship must stay held — phase=" + vesselPhase("C001"));
        assertTrue(hm2.activeVessels().get("C001").weatherHeld());
        assertEquals(awardsAfterLoad, awardCount("C001"), "no tug is hired for a held ship");
    }

    /**
     * Decision B7, born red: right after a load taken in a storm the gate is CLOSED — before any
     * weather message has reached the rebuilt HarbourMaster. Pre-S16 it had no reading until the
     * first broadcast (up to fifteen game minutes, longer under a pause) and treated "no
     * reading" as safe: a ship arriving in that window was granted, escorted and sent in.
     */
    @Test
    void afterAMidStormLoad_theGateIsClosedBeforeAnyWeatherMessageArrives() throws Exception {
        startPausedSession();
        awaitHarbourMaster();
        // The weather agent's cell - and so the save - is a storm. Published until the cell shows
        // it (the agent may still be booting when the HarbourMaster is already up), and the save
        // waits for the rest of the roster too: a snapshot of a half-booted world is not a save.
        assertTrue(waitUntil(() -> {
            boot.getEventBus().publish(STORM);
            boolean stormInTheCell = session.directory().byName("weather_agent")
                    .map(a -> ((it.unige.portcommand.agents.WeatherAgent) a).snapshotDto().current())
                    .map(w -> "stormy".equals(w.state())).orElse(false);
            return stormInTheCell && java.util.List.of("tug_1", "tug_2", "tug_3", "tug_4",
                            "terminal_container", "terminal_general", "customs_agent").stream()
                    .allMatch(name -> session.directory().byName(name).isPresent());
        }, 15_000), "staging: the whole roster is up and the weather agent's reading is the storm");
        Path saved = saveNowRetrying(session, 15_000);

        session.loadGame(saved);
        HarbourMasterAgent hm = awaitHarbourMaster();
        boot.getSimClock().pause(); // nothing is broadcast under a pause: only the boot reading exists
        assertNotNull(hm.lastWeather(), "the rebuilt HarbourMaster has a reading from its first instant");
        assertEquals("stormy", hm.lastWeather().state(), "and it is the SAVED one");

        spawnContracted("C001");
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("C001");
            return t != null && t.stage() == Stage.AWAITING_TUG && t.weatherHeld();
        }, 15_000), "a ship arriving right after the load is granted its berth and HELD at once — "
                + "tracking=" + hm.activeVessels().get("C001"));
        assertFalse(waitUntil(() -> awardCount("C001") > 0, 2_000),
                "no tug is hired into the storm (pre-S16: the Contract Net ran, nobody had told the "
                        + "new HarbourMaster about the weather)");
        assertFalse(hm.movement().holdRules("C001").isEmpty(), "the hold names its rules");
    }

    /**
     * Tugs waiting ON STATION survive a save and a load (S16; a gap the adversarial review
     * named). The HarbourMaster does not save who is on station: a restored tug reports again,
     * the restored HarbourMaster rebuilds its notes from the reports, the customs inspection the
     * ship was waiting for starts again, and when it ends the ship is ordered in with the SAME
     * two tugs — nobody is hired or charged a second time.
     *
     * <p>The cargo is hazmat on purpose: it is always inspected (R22, R24), for 45 game minutes,
     * and even the slowest tug is there sooner (about 35.5 since S19) — so there are minutes in
     * which the whole escort is on station and the ship still may not move. That is the state
     * saved here; the test first checks that the tug leg's jump still ends inside the inspection.
     */
    @Test
    void tugsOnStationSurviveSaveLoad_andTheShipGoesInWithThemOnceCustomsClears() throws Exception {
        assertTrue(TUGS_REACH_SHIP_SIM_SECONDS < INSPECTION_SIM_SECONDS,
                "staging: the slowest tug's run with the margin (" + TUGS_REACH_SHIP_SIM_SECONDS
                        + " s) must end inside the inspection (" + INSPECTION_SIM_SECONDS
                        + " s), or no escort ever waits on station for customs");
        startPausedSession();
        HarbourMasterAgent hm = awaitHarbourMaster();
        VesselSpec hazmat = new VesselSpec("C001", "cargo_vessel", 9.5, 180.0, 40_000, "hazmat_class_1", 0L);
        AgentRoster.spawnContractedVessel(boot.getSpawner(), hazmat, "CONTRACT-1", boot.getSimClock(),
                boot.getMarketHistoryArtifact(), session.directory());
        assertNotNull(awaitEvent(TugJobAwardedEvent.class, e -> "C001".equals(e.vesselId()), 15_000),
                "staging: the escort is hired");
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("C001");
            return t != null && t.assignedTugs().size() == 2
                    && hm.pendingCustomsChecks().containsValue("C001");
        }, 10_000), "staging: two tugs assigned and customs still inspecting — tracking="
                + hm.activeVessels().get("C001"));

        awaitEscortUnderWay(hm, "C001");
        jump(TUGS_REACH_SHIP_SIM_SECONDS); // ends inside the inspection's 45 game minutes
        assertTrue(waitUntil(() -> hm.movement().onStation("C001").size() == 2, 15_000),
                "staging: both tugs have reached the ship — on station: " + hm.movement().onStation("C001"));
        assertEquals(Stage.AWAITING_TUG, hm.activeVessels().get("C001").stage(),
                "escort complete and on station, but customs has not cleared her: she does not move");
        assertEquals(BaseVesselAgent.FlowPhase.AWAITING_CLEARANCE, vesselPhase("C001"));

        Path saved = saveNowRetrying(session, 15_000);
        var savedState = new SaveLoadManager(tempDir).load(saved);
        assertEquals(2, savedState.agents().tugs().stream()
                        .filter(t -> "ON_STATION".equals(t.status()) && t.job() != null
                                && "C001".equals(t.job().vesselId())).count(),
                "the save holds two tugs on station for C001: " + savedState.agents().tugs());
        long awardsBeforeLoad = awardCount("C001");

        session.loadGame(saved);
        HarbourMasterAgent hm2 = awaitHarbourMaster();
        boot.getSimClock().pause(); // the load resumed it
        assertTrue(waitUntil(() -> hm2.activeVessels().get("C001") != null, 10_000));
        assertEquals(Stage.AWAITING_TUG, hm2.activeVessels().get("C001").stage(),
                "nothing is ordered in merely because a save was loaded: customs is asked again first");

        // Customs is asked again by the restore (once its agent is back in the directory, a
        // second or so of real time) and starts the inspection over. Game time is moved only
        // after that: the 45 minutes are counted from the moment the inspection starts.
        assertTrue(waitUntil(() -> hm2.movement().inspectionRules("C001").equals(java.util.List.of("R22", "R24")),
                15_000), "the restarted inspection is announced to the rebuilt HarbourMaster — "
                + hm2.movement().inspectionRules("C001"));

        // One game minute: each restored tug finds itself where it already is and reports again.
        jump(60L);
        assertTrue(waitUntil(() -> hm2.movement().onStation("C001").size() == 2, 15_000),
                "the restored tugs report on station again and the HarbourMaster notes it — "
                        + hm2.movement().onStation("C001"));
        assertEquals(BaseVesselAgent.FlowPhase.AWAITING_CLEARANCE, vesselPhase("C001"),
                "still waiting: the inspection started again with the load");

        jump(46 * 60L); // the restarted inspection, and a minute
        assertTrue(waitUntil(() -> vesselPhase("C001") == BaseVesselAgent.FlowPhase.TRANSIT, 15_000),
                "cleared, escort on station: the restored ship is ordered to proceed — phase="
                        + vesselPhase("C001") + " tracking=" + hm2.activeVessels().get("C001"));
        assertEquals(awardsBeforeLoad, awardCount("C001"),
                "with the tugs she already had: no tug is hired a second time");
        jump(CHANNEL_LEG_SIM_SECONDS);
        assertTrue(waitUntil(() -> vesselPhase("C001") == BaseVesselAgent.FlowPhase.DOCKED, 15_000),
                "and she docks — phase=" + vesselPhase("C001"));
        assertEquals(1, hm2.walletLedger().expenseHistory().stream()
                        .filter(e -> it.unige.portcommand.harbourmaster.financial.ExpenseRules.SOURCE_CUSTOMS
                                .equals(e.source()))
                        .filter(e -> "C001".equals(e.vesselId())).count(),
                "one customs fee, although the inspection ran twice");
    }

    // ---- staging ---------------------------------------------------------------------------

    private void startPausedSession() throws Exception {
        boot = new JadeBootstrap();
        session = new GameSession(boot,
                new BootstrapConfig(TEST_PORT, false, "realtime", 300),
                new SaveLoadManager(tempDir), new Leaderboard(tempDir.resolve("scores.json")));
        session.startNewGame();
        // startNewGame runs the production WallClockAdvancer (288 game seconds per wall second
        // here). Pausing freezes game time while every message-driven step — announce, grant,
        // Contract Net, alert delivery — proceeds on wall time, and gates Poisson spawns off.
        boot.getSimClock().pause();
    }

    /**
     * C001 (CONTRACT-1 → berth_1, cargo 40 kt → 2 tugs) announces, is granted and has its
     * escort HIRED — tugs assigned, none arrived, the ship at the channel entry — and then an
     * injected storm holds it. Returns the HarbourMaster once the hold is in the tracking and
     * the ship has consumed it.
     */
    private HarbourMasterAgent bootAndHoldC001AtTheChannelEntry() throws Exception {
        startPausedSession();
        spawnContracted("C001");
        HarbourMasterAgent hm = awaitHarbourMaster();

        assertNotNull(awaitEvent(TugJobAwardedEvent.class, e -> "C001".equals(e.vesselId()), 15_000),
                "staging: the escort is hired");
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("C001");
            return t != null && t.assignedTugs().size() == 2 && t.stage() == Stage.AWAITING_TUG;
        }, 10_000), "staging: two tugs assigned, the ship still waiting — tracking="
                + hm.activeVessels().get("C001"));
        assertTrue(waitUntil(() -> vesselPhase("C001") == BaseVesselAgent.FlowPhase.AWAITING_CLEARANCE,
                10_000), "staging: the ship holds its grant and waits for the order to proceed");

        boot.getEventBus().publish(STORM);
        injectAlert(hm, "weather_threshold", 32, "poor", 4.0, "stormy");
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get("C001");
            return t != null && t.weatherHeld();
        }, 15_000), "staging: the storm must HOLD the waiting ship (pre-S16 only a ship "
                + "'in transit with tugs' could be held) — tracking=" + hm.activeVessels().get("C001"));
        return hm;
    }

    /**
     * Waits until both hired tugs have TAKEN their award and are under way to the ship. A jump
     * may only follow this: a tug counts its leg from the moment it accepts, so a tug whose
     * ACCEPT is read a millisecond after the jump has not moved at all - and under the pause it
     * never will. (The award event the cases wait on is published by the HarbourMaster on
     * sending, before either tug has read anything.)
     */
    private void awaitEscortUnderWay(HarbourMasterAgent hm, String vesselId) throws InterruptedException {
        assertTrue(waitUntil(() -> {
            var t = hm.activeVessels().get(vesselId);
            return t != null && t.assignedTugs().size() == 2 && t.assignedTugs().stream()
                    .allMatch(tug -> session.directory().byName(tug)
                            .map(a -> ((it.unige.portcommand.agents.TugAgent) a).status()
                                    == it.unige.portcommand.agents.TugStatus.EN_ROUTE_TO_VESSEL)
                            .orElse(false));
        }, 15_000), "staging: both tugs have accepted and are under way — tracking="
                + hm.activeVessels().get(vesselId));
    }

    private void spawnContracted(String vesselId) {
        VesselSpec spec = new VesselSpec(vesselId, "cargo_vessel", 9.5, 180.0, 40_000, "general_cargo", 0L);
        AgentRoster.spawnContractedVessel(boot.getSpawner(), spec, "CONTRACT-1", boot.getSimClock(),
                boot.getMarketHistoryArtifact(), session.directory());
    }

    /**
     * Neither {@code startNewGame} nor {@code loadGame} guarantees the HarbourMaster's directory
     * entry on return (it registers as the last step of its setup) — bounded wait, S15.
     */
    private HarbourMasterAgent awaitHarbourMaster() throws InterruptedException {
        assertTrue(waitUntil(() -> session.directory().byName("harbour_master").isPresent(), 15_000),
                "fixture: the HarbourMaster must be registered");
        return (HarbourMasterAgent) session.directory().byName("harbour_master").orElseThrow();
    }

    /**
     * Moves game time on by exactly {@code simSeconds} and pauses again: resume, one explicit
     * advance, pause. The production advancer may slip one 100 ms tick in (29 game seconds at
     * this pace), which every budget here allows for.
     */
    /** The slowest tug's run from its base to the channel entry, from the production tables. */
    private static long slowestTugRunSimSeconds() {
        double slowestMinutes = TugProfiles.all().entrySet().stream()
                .mapToDouble(tug -> TugMath.etaMinutes(
                        TugMath.distanceKm(TugBasePositions.position(tug.getKey()),
                                AutoFlowDispatcherBehaviour.CHANNEL_ENTRY),
                        tug.getValue().topSpeedKnots()))
                .max().orElseThrow();
        return (long) Math.ceil(slowestMinutes * 60.0);
    }

    private void jump(long simSeconds) {
        SimClock clock = boot.getSimClock();
        clock.resume();
        clock.advance(clock.simSecondsToWallMs(simSeconds));
        clock.pause();
    }

    /** One probe per live world (the container teardown on load kills it — respawned lazily). */
    private int probeSerial;

    /**
     * Hand-crafts a protocol-tagged weather alert (the exact shape
     * {@code PeriodicWeatherBroadcastBehaviour} emits, per HarbourMasterAgentIT's builders) and
     * sends it through a fresh {@link HmProbeAgent}. Deterministic and pause-proof.
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

    private BaseVesselAgent.FlowPhase vesselPhase(String vesselId) {
        return session.directory().byName("vessel_" + vesselId.toLowerCase())
                .filter(BaseVesselAgent.class::isInstance)
                .map(a -> ((BaseVesselAgent) a).flowPhase())
                .orElse(null);
    }

    private long awardCount(String vesselId) {
        return EventBusProbe.published(boot.getEventBus()).stream()
                .filter(TugJobAwardedEvent.class::isInstance).map(TugJobAwardedEvent.class::cast)
                .filter(e -> vesselId.equals(e.vesselId()))
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
