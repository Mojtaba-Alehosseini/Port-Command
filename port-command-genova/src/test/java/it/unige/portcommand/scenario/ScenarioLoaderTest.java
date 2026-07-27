package it.unige.portcommand.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.prolog.PrologQueries;
import it.unige.portcommand.scenario.events.BerthFloodEvent;
import it.unige.portcommand.scenario.events.NotificationScriptedEvent;
import it.unige.portcommand.scenario.events.SpawnVesselEvent;
import it.unige.portcommand.scenario.events.TugRefuelCompleteEvent;
import it.unige.portcommand.scenario.events.TutorialStepAdvanceEvent;
import it.unige.portcommand.scenario.events.WeatherChangeScriptedEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip + schema validation for the 3 packaged scenarios (planning/23 §23.11),
 * table-driven against the §3.15 numbers, plus the compat guarantee behind
 * {@code ScenarioVessels}' pinned dims: every contracted (berth × type) pair a scenario
 * or {@code contracts.json} can spawn passes the live Prolog gate (RULES R1–R8) — a
 * scripted arrival must never be REFUSE'd by its own contract's berth.
 */
class ScenarioLoaderTest {

    private final ScenarioRegistry registry = new ScenarioRegistry();
    private final ScenarioLoader loader = new ScenarioLoader();

    // ---- the 3 packaged scenarios: presence + §3.15 numbers ----

    @Test
    void exactlyThreeScenariosLoadAndValidate() {
        assertEquals(List.of("tutorial", "busy_day", "storm"), ScenarioRegistry.KEYS);
        assertEquals(3, registry.all().size(), "exactly 3 scenarios in v1 — no freeform, no 4th/5th");
    }

    @Test
    void tutorialMatchesTheCanonicalStartState() {
        Scenario s = registry.findByKey("tutorial").orElseThrow();
        // Canonical demo-path start (planning/23 §23.5): Day 1, 08:00, €15,000, rep 50.
        assertEquals(1, s.initialState().startDay());
        assertEquals("08:00", s.initialState().startTimeOfDay());
        assertEquals(15_000.0, s.initialState().wallet());
        assertEquals(50, s.initialState().reputation());
        assertEquals(20260101L, s.randomSeed());
        assertEquals("EASY", s.settingsOverride().difficulty());
        assertEquals(8, s.initialState().weatherOverride().windKn());
        // Two vessels: contracted C-501 at 300s, pinned NEUTRAL walk-in at 900s.
        List<SpawnVesselEvent> spawns = eventsOf(s, SpawnVesselEvent.class);
        assertEquals(2, spawns.size());
        assertEquals("C-501", spawns.get(0).contractRef());
        assertEquals(300, spawns.get(0).simTimeSeconds());
        assertEquals("NEUTRAL", spawns.get(1).personality());
        assertEquals(1400.0, spawns.get(1).minPrice());
        assertEquals(1900.0, spawns.get(1).targetPrice());
        assertEquals(900, spawns.get(1).simTimeSeconds());
        // All 5 tutorial steps, in order.
        List<TutorialStepAdvanceEvent> steps = eventsOf(s, TutorialStepAdvanceEvent.class);
        assertEquals(List.of(1, 2, 3, 4, 5), steps.stream().map(TutorialStepAdvanceEvent::step).toList());
    }

    @Test
    void busyDayMatchesItsSpec() {
        Scenario s = registry.findByKey("busy_day").orElseThrow();
        assertEquals(25_000.0, s.initialState().wallet());
        assertEquals(60, s.initialState().reputation());
        // Tug 1 refuelling at boot; back in service at 720s.
        Scenario.TugInit tug1 = s.initialState().tugs().stream()
                .filter(t -> t.tugId() == 1).findFirst().orElseThrow();
        assertEquals("REFUELING", tug1.status());
        assertEquals(720, eventsOf(s, TugRefuelCompleteEvent.class).get(0).simTimeSeconds());
        List<SpawnVesselEvent> spawns = eventsOf(s, SpawnVesselEvent.class);
        // 3 contracted spawns within 120s.
        List<SpawnVesselEvent> contracted = spawns.stream()
                .filter(e -> SpawnVesselEvent.CHANNEL_CONTRACTED.equals(e.channel())).toList();
        assertEquals(3, contracted.size());
        assertTrue(contracted.stream().allMatch(e -> e.simTimeSeconds() <= 120));
        // 2 simultaneous walk-ins at 300s + the AGGRESSIVE tanker at 900s.
        List<SpawnVesselEvent> walkIns = spawns.stream()
                .filter(e -> SpawnVesselEvent.CHANNEL_WALK_IN.equals(e.channel())).toList();
        assertEquals(3, walkIns.size());
        assertEquals(2, walkIns.stream().filter(e -> e.simTimeSeconds() == 300).count());
        SpawnVesselEvent tanker = walkIns.stream()
                .filter(e -> "tanker".equals(e.vesselType())).findFirst().orElseThrow();
        assertEquals(900, tanker.simTimeSeconds());
        assertEquals("AGGRESSIVE", tanker.personality());
        // 2026-07-27 (task 26): the scripted terminal-flood — the DISCONFIRM trigger
        // PROJECT_DEFINITION §13.3 has always attributed to Busy Day but which nothing in the
        // shipped game ever fired. berth_2 is deliberate: terminal_container is the only terminal
        // that manages it (AgentRoster), so exactly one terminal retracts and the other ignores
        // the broadcast, which is the behaviour RetractIfFloodBehaviour documents.
        List<BerthFloodEvent> floods = eventsOf(s, BerthFloodEvent.class);
        assertEquals(1, floods.size(), "busy_day carries exactly one scripted flood");
        assertEquals("berth_2", floods.get(0).berthId());
        assertEquals(780, floods.get(0).simTimeSeconds());
    }

    @Test
    void stormMatchesItsSpec() {
        Scenario s = registry.findByKey("storm").orElseThrow();
        assertEquals(30_000.0, s.initialState().wallet());
        assertEquals(70, s.initialState().reputation());
        // 2 berths occupied by pre-existing docked cargo vessels. (Reconciled 2026-07-18:
        // berth_2+berth_3, not the sketch's B1/B2 — berth_1 is the only tanker-capable
        // berth and the storm's own tanker must be grantable; see planning/23.)
        List<Scenario.BerthInit> occupied = s.initialState().berths().stream()
                .filter(Scenario.BerthInit::occupied).toList();
        assertEquals(2, occupied.size());
        assertTrue(occupied.stream().allMatch(b -> "cargo_vessel".equals(b.vessel().vesselType())));
        // Storm at 990s (crosses the 30-kn tanker limit), clear at 1980s.
        //
        // THESE TIMES ARE LOAD-BEARING (retimed 2026-07-27, task 26 — was 480/1500). The storm
        // must strike while the tanker is IN TRANSIT WITH TUGS, because that is the only state
        // HandleWeatherAlertBehaviour cancels an escort from, and that window is exactly
        // TransitToBerthBehaviour.TRANSIT_SIM_MILLIS = 120 sim-s wide. At 480 the tanker (spawned
        // at t=0) had been docked for six sim-minutes, so the storm scenario's whole reason to
        // exist — the CANCEL performative PROJECT_DEFINITION §13 assigns to it — never fired in a
        // real playthrough; `--smoke` found it. The spawn also moved to 960 so the first
        // 15-sim-minute weather broadcast (t=900) can establish the alert baseline first:
        // WeatherAlertPolicy.transition treats a null previous reading as "no transition", so a
        // storm before that tick is silent no matter how it is timed.
        List<WeatherChangeScriptedEvent> weather = eventsOf(s, WeatherChangeScriptedEvent.class);
        assertEquals(2, weather.size());
        assertEquals(990, weather.get(0).simTimeSeconds());
        assertEquals(32, weather.get(0).windKn());
        assertEquals(1980, weather.get(1).simTimeSeconds());
        assertEquals(24, weather.get(1).windKn());
        // The EMERGENCY banner rides the storm moment.
        assertTrue(eventsOf(s, NotificationScriptedEvent.class).stream()
                .anyMatch(n -> "EMERGENCY".equals(n.severity()) && n.simTimeSeconds() == 990));
        // The tanker enters the world 60 sim-s BEFORE the storm — the margin that keeps it in
        // transit when the alert lands (120 sim-s transit, so ~58 sim-s of slack either side).
        SpawnVesselEvent tanker = eventsOf(s, SpawnVesselEvent.class).stream()
                .filter(e -> SpawnVesselEvent.CHANNEL_CONTRACTED.equals(e.channel()))
                .findFirst().orElseThrow();
        assertEquals(960, tanker.simTimeSeconds(), "tanker spawn must precede the storm");
        long gap = weather.get(0).simTimeSeconds() - tanker.simTimeSeconds();
        assertTrue(gap > 0 && gap <= 30,
                "the storm must land EARLY inside the tanker's 120 sim-s transit, not late: gap="
                        + gap + " sim-s. 30 is one ScriptedEventBehaviour tick, the smallest gap the "
                        + "engine can express, and the margin that matters is REAL time, not sim time: "
                        + "at the storm's 900 s/day the whole 120 sim-s window is 1.25 WALL seconds. "
                        + "The first version of this fix used a 60 sim-s gap and left only ~330 ms "
                        + "between the hold and the docking (measured across five runs), because the "
                        + "scripted event itself fires up to a tick late. See docs/BUGS_FOUND.md "
                        + "BUG-02.");
    }

    // ---- seed rule ----

    @Test
    void nullSeedMeansGenerateAndZeroIsAValidPin() throws Exception {
        Scenario nullSeed = loadFixtureWithSeed("null");
        assertNull(nullSeed.randomSeed(), "null seed → generate at scenario start");
        Scenario zeroSeed = loadFixtureWithSeed("0");
        assertEquals(0L, zeroSeed.randomSeed(), "0 is a VALID pinned seed, never a sentinel");
    }

    // ---- validation negatives ----

    @Test
    void malformedFixtureWithEventsOutOfOrderIsRejected() {
        ScenarioValidationException e = assertThrows(ScenarioValidationException.class,
                () -> loadResource("/scenariofixtures/malformed.json"));
        assertTrue(e.getMessage().contains("sorted"), "names the sort violation: " + e.getMessage());
    }

    @Test
    void aScenarioContractShadowingAGlobalOneIsRejected() throws Exception {
        String json = tutorialJson().replace("\"contract_id\": \"C-501\"",
                "\"contract_id\": \"CONTRACT-1\"");
        ScenarioValidationException e = assertThrows(ScenarioValidationException.class,
                () -> loader.load(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "shadow"));
        assertTrue(e.getMessage().contains("shadows"), e.getMessage());
    }

    @Test
    void aDanglingContractRefIsRejected() throws Exception {
        String json = tutorialJson().replace("\"contractRef\": \"C-501\"",
                "\"contractRef\": \"NO-SUCH-CONTRACT\"");
        assertThrows(ScenarioValidationException.class,
                () -> loader.load(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "dangling"));
    }

    @Test
    void anUnknownJsonFieldIsRejectedNotSilentlyIgnored() throws Exception {
        String json = tutorialJson().replace("\"randomSeed\": 20260101,",
                "\"randomSeed\": 20260101, \"randomSeeed\": 1,");
        assertThrows(ScenarioValidationException.class,
                () -> loader.load(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "typo"),
                "a typo in a hand-authored field must fail, not vanish");
    }

    // ---- round-trip ----

    @Test
    void everyPackagedScenarioRoundTripsThroughItsOwnMapper() throws Exception {
        for (Scenario s : registry.all()) {
            String json = loader.mapper().writeValueAsString(s);
            Scenario back = loader.load(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    s.key() + "-roundtrip");
            assertEquals(s, back, "load → serialize → reload must be identity for " + s.key());
        }
    }

    // ---- the compat guarantee behind scripted contracted spawns ----

    /**
     * Every contract a scenario (or the daily {@code contracts.json} stream) can spawn
     * must pass RULES R1–R8 at its pinned berth with {@code ScenarioVessels}' pinned
     * dims — the Prolog gate is queried live (the same call
     * {@code AutoFlowDispatcherBehaviour} makes before any grant).
     */
    @Test
    void everySpawnableContractIsCompatibleWithItsBerthUnderPinnedDims() {
        it.unige.portcommand.prolog.PrologEngine.getInstance().init(); // idempotent consult
        java.util.List<ServiceContract> all = new java.util.ArrayList<>(
                AgentRoster.loadContracts().values());
        for (Scenario s : registry.all()) {
            all.addAll(s.contracts());
        }
        assertEquals(5, all.size(), "2 global + C-501 + BD-503 + ST-901");
        for (ServiceContract contract : all) {
            double[] dims = ScenarioVessels.pinnedDims(contract.vesselType());
            assertTrue(PrologQueries.isCompatible(contract.berthId(), contract.vesselType(),
                            dims[0], dims[1], (int) dims[2]),
                    contract.contractId() + ": " + contract.vesselType() + " (pinned dims) must be"
                            + " compatible with " + contract.berthId());
        }
    }

    /** Day-qualified daily clones keep type/berth/fee and derive ETA on the target day. */
    @Test
    void dailyContractClonesAreDayQualifiedEverywhereTheEngineNeedsThem() {
        ServiceContract base = AgentRoster.loadContracts().get("CONTRACT-1");
        ServiceContract d3 = ContractSchedule.dailyContract(base, 3);
        assertEquals("CONTRACT-1-D3", d3.contractId(), "fee idempotency key is per-day");
        assertEquals("C001-D3", d3.vesselId(), "vessel local names never collide across days");
        assertEquals(2 * 86_400_000L + base.expectedArrivalSimMillis(), d3.expectedArrivalSimMillis());
        assertEquals("contract:CONTRACT-1:d3", ContractSchedule.eventId(base, 3));
        assertEquals((d3.expectedArrivalSimMillis() - ContractSchedule.SPAWN_LEAD_SIM_MILLIS) / 1000,
                ContractSchedule.spawnSimSeconds(d3), "spawn leads the ETA by 2 sim-hours");
    }

    // ---- helpers ----

    private <T> List<T> eventsOf(Scenario s, Class<T> type) {
        return s.events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private Scenario loadResource(String resource) throws IOException, ScenarioValidationException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return loader.load(in, resource);
        }
    }

    private String tutorialJson() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/data/scenarios/tutorial.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Scenario loadFixtureWithSeed(String seedLiteral) throws Exception {
        String json = tutorialJson().replace("\"randomSeed\": 20260101", "\"randomSeed\": " + seedLiteral);
        return loader.load(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "seed-" + seedLiteral);
    }

    /** The §3.15 pacing reconciliation (2026-07-18): scenario day lengths are pinned. */
    @Test
    void scenarioPacingOverridesAreThePinnedValues() {
        assertEquals(1800L, registry.findByKey("tutorial").orElseThrow()
                .settingsOverride().realSecondsPerGameDay());
        assertEquals(900L, registry.findByKey("busy_day").orElseThrow()
                .settingsOverride().realSecondsPerGameDay());
        assertEquals(900L, registry.findByKey("storm").orElseThrow()
                .settingsOverride().realSecondsPerGameDay());
    }

    /** Global contracts carry the new task-23 fields the daily stream needs. */
    @Test
    void globalContractsCarryVesselTypeAndTimeOfDayEta() {
        Map<String, ServiceContract> global = AgentRoster.loadContracts();
        assertEquals("cargo_vessel", global.get("CONTRACT-1").vesselType());
        assertEquals(9 * 3_600_000L, global.get("CONTRACT-1").expectedArrivalSimMillis(), "09:00");
        assertEquals("ferry", global.get("CONTRACT-2").vesselType());
        assertEquals(15 * 3_600_000L, global.get("CONTRACT-2").expectedArrivalSimMillis(), "15:00");
    }

    /** Storm's tanker contract is scenario-local and must not shadow globals. */
    @Test
    void scenarioLocalContractsResolveForTheirSpawnEvents() {
        Scenario storm = registry.findByKey("storm").orElseThrow();
        SpawnVesselEvent tankerSpawn = eventsOf(storm, SpawnVesselEvent.class).stream()
                .filter(e -> SpawnVesselEvent.CHANNEL_CONTRACTED.equals(e.channel()))
                .findFirst().orElseThrow();
        assertEquals("ST-901", tankerSpawn.contractRef());
        ServiceContract local = storm.contracts().get(0);
        assertEquals("tanker", local.vesselType());
        assertEquals("berth_1", local.berthId(), "the only tanker-capable berth stays free at boot");
        // The t=0 "tanker due" WARN now OPENS the script (index 0) and the spawn follows at
        // t=960 — retimed 2026-07-27 (task 26) so the storm can catch the tanker mid-transit;
        // see stormMatchesItsSpec() for why those numbers are load-bearing.
        assertInstanceOf(NotificationScriptedEvent.class, storm.events().get(0),
                "the tanker-due warning opens the script, ahead of the spawn");
    }
}
