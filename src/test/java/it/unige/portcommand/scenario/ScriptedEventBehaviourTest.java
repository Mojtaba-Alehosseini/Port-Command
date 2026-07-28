package it.unige.portcommand.scenario;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.JadeAgentSpawner;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.scenario.events.NotificationScriptedEvent;
import it.unige.portcommand.scenario.events.ScriptedEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.EventBusProbe;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queue mechanics of the scenario engine (planning/23 §23.11): firing order,
 * exactly-once, the save/load no-replay reconstruction, Poisson-suppression windows,
 * and the daily contracted stream's materialisation rules. Uses bus-only scripted
 * events (notifications) plus a mocked spawner so no container is needed; the real
 * spawn path is {@code ScenarioSessionIT}'s job. Ticks are driven directly through
 * {@code onSimTick()} (the behaviour and snapshot share the HM thread in production;
 * here the test thread stands in for it).
 */
class ScriptedEventBehaviourTest {

    private SimClock clock;
    private EventBus bus;
    private Map<String, ServiceContract> contracts;
    private GameContext ctx;

    @BeforeEach
    void setUp() {
        clock = new SimClock(300);
        bus = new EventBus();
        contracts = new ConcurrentHashMap<>();
        ctx = new GameContext(contracts, clock, Mockito.mock(JadeAgentSpawner.class),
                null, new RandomSource(42), bus, null);
    }

    /** Starts at midnight (offset 0) so every OTHER test's raw {@code jumpTo(N)} literals
     * mean exactly what they say — "N sim-seconds since scenario start". The offset
     * translation itself (non-midnight starts, the 2026-07-18 bug) has its own dedicated
     * tests below, which build their own non-midnight scenario explicitly. */
    private static Scenario scenarioOf(ScriptedEvent... events) {
        return scenarioStartingAt("00:00", events);
    }

    private static Scenario scenarioStartingAt(String startTimeOfDay, ScriptedEvent... events) {
        return new Scenario("testsc", "Test", "test scenario", 1_000.0, 7L, null,
                new Scenario.InitialState(1, startTimeOfDay, 1_000.0, 50,
                        new Scenario.WeatherInit(10, "good", 0.5, "sunny"), List.of(), List.of()),
                List.of(), List.of(events));
    }

    private static NotificationScriptedEvent note(long simSeconds, String text) {
        return new NotificationScriptedEvent(simSeconds, "INFO", text);
    }

    private void jumpTo(long simSeconds) {
        clock.restore(simSeconds * 1000L, 300);
        clock.resume();
    }

    private List<String> publishedTexts() {
        return EventBusProbe.published(bus).stream()
                .filter(NotificationEvent.class::isInstance)
                .map(e -> ((NotificationEvent) e).text())
                .toList();
    }

    // ---- firing order + exactly-once ----

    @Test
    void eventsFireInDeclaredOrderOnceTheirSimTimeIsReached() {
        Scenario s = scenarioOf(note(100, "first"), note(200, "second"), note(600, "third"));
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.of()), List.of());

        jumpTo(50);
        behaviour.onSimTick();
        assertEquals(List.of(), publishedTexts(), "nothing due before its sim time");

        jumpTo(250);
        behaviour.onSimTick();
        assertEquals(List.of("first", "second"), publishedTexts(), "due events fire, in order");

        behaviour.onSimTick();
        assertEquals(List.of("first", "second"), publishedTexts(), "a second tick never re-fires");

        jumpTo(600);
        behaviour.onSimTick();
        assertEquals(List.of("first", "second", "third"), publishedTexts());
        assertEquals(List.of("testsc#0", "testsc#1", "testsc#2"), behaviour.firedEventIdsSorted());
    }

    @Test
    void nothingFiresWhileTheClockIsPaused() {
        Scenario s = scenarioOf(note(100, "held"));
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.of()), List.of());
        clock.restore(200_000L, 300); // restore leaves the clock PAUSED
        behaviour.onSimTick();
        assertEquals(List.of(), publishedTexts(), "paused game — the timeline holds");
        clock.resume();
        behaviour.onSimTick();
        assertEquals(List.of("held"), publishedTexts());
    }

    // ---- save/load no-replay ----

    @Test
    void restartMidScenarioDoesNotReplayFiredEvents() {
        Scenario s = scenarioOf(note(100, "first"), note(200, "second"), note(600, "third"));
        ScriptedEventBehaviour first = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.of()), List.of());
        jumpTo(250);
        first.onSimTick();
        List<String> persisted = first.firedEventIdsSorted(); // what the save carries

        // The task-22 teardown/rebuild: a NEW behaviour, re-armed from the persisted ids.
        ScriptedEventBehaviour rebuilt = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.copyOf(persisted)), List.of());
        rebuilt.onSimTick();
        assertEquals(List.of("first", "second"), publishedTexts(),
                "already-fired events must NOT replay after a load");

        jumpTo(600);
        rebuilt.onSimTick();
        assertEquals(List.of("first", "second", "third"), publishedTexts(),
                "the not-yet-fired tail still fires in the restored timeline");
        assertEquals(List.of("testsc#0", "testsc#1", "testsc#2"), rebuilt.firedEventIdsSorted());
    }

    // ---- scenario-start offset (2026-07-18: found by an actual play-through — every
    // packaged scenario boots at 08:00, and the un-fixed engine compared each event's
    // declared simTimeSeconds directly against the ABSOLUTE clock, so all 7 tutorial
    // events fired within one 30-sim-s tick instead of across their intended ~31 wall-s
    // spread. Regression-guarded here with an explicit non-midnight scenario start,
    // decoupled from every other test's midnight-start convenience helper.) ----

    @Test
    void startSimMillisIsMidnightPlusTheDeclaredTimeOfDay() {
        assertEquals(0L, new Scenario.InitialState(1, "00:00", 0, 0, null, List.of(), List.of())
                .startSimMillis());
        assertEquals(8 * 3_600_000L,
                new Scenario.InitialState(1, "08:00", 0, 0, null, List.of(), List.of())
                        .startSimMillis());
        assertEquals(86_400_000L + 8 * 3_600_000L + 30 * 60_000L,
                new Scenario.InitialState(2, "08:30", 0, 0, null, List.of(), List.of())
                        .startSimMillis(), "day 2 adds a full MILLIS_PER_DAY");
    }

    @Test
    void scenarioEventsAreRelativeToScenarioStartNotMidnight() {
        // The exact shape of the packaged scenarios: boots at 08:00 (28,800 abs sim-s),
        // events declared at small offsets (100, 500) from THAT start.
        Scenario s = scenarioStartingAt("08:00", note(100, "first"), note(500, "second"));
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.of()), List.of());

        clock.restore(500L * 1000L, 300); // raw absolute 500s — the PRE-FIX due instant
        clock.resume();
        behaviour.onSimTick();
        assertEquals(List.of(), publishedTexts(),
                "at 08:00 + 500 abs-sim-s the scenario has been running only ~8h08m20s — "
                        + "neither event (due at 08:00+100s and 08:00+500s) is due yet");

        jumpTo(8 * 3600 + 100); // scenario-start + 100s — the first event's TRUE due instant
        behaviour.onSimTick();
        assertEquals(List.of("first"), publishedTexts(), "fires exactly at start+100s");

        jumpTo(8 * 3600 + 500); // scenario-start + 500s
        behaviour.onSimTick();
        assertEquals(List.of("first", "second"), publishedTexts(), "fires exactly at start+500s");
    }

    @Test
    void aFiveDayTwoTutorialStepsAreNotAllFiredByTheFirstTick() {
        // The precise regression: the tutorial's own 5 steps (0, 200, 600, 1100, 1500),
        // observed firing within one 30-sim-s tick of an 08:00 boot pre-fix.
        Scenario s = scenarioStartingAt("08:00",
                new it.unige.portcommand.scenario.events.TutorialStepAdvanceEvent(0, 1, "s1"),
                new it.unige.portcommand.scenario.events.TutorialStepAdvanceEvent(200, 2, "s2"),
                new it.unige.portcommand.scenario.events.TutorialStepAdvanceEvent(600, 3, "s3"),
                new it.unige.portcommand.scenario.events.TutorialStepAdvanceEvent(1100, 4, "s4"),
                new it.unige.portcommand.scenario.events.TutorialStepAdvanceEvent(1500, 5, "s5"));
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.of()), List.of());

        jumpTo(8 * 3600); // the scenario's own opening instant — only step 1 (offset 0) is due
        behaviour.onSimTick();
        assertEquals(1, behaviour.firedEventIdsSorted().size(),
                "only the offset-0 step fires at the scenario's opening instant, not all 5");

        jumpTo(8 * 3600 + 700); // between steps 3 (600) and 4 (1100)
        behaviour.onSimTick();
        assertEquals(3, behaviour.firedEventIdsSorted().size(), "steps 1-3 due, 4-5 still ahead");

        jumpTo(8 * 3600 + 1500);
        behaviour.onSimTick();
        assertEquals(5, behaviour.firedEventIdsSorted().size(), "all 5 due by start+1500s");
    }

    // ---- Poisson suppression window ----

    @Test
    void scenarioWaveSuppressesUntilTheLastScriptedEventAndSurvivesReload() {
        Scenario s = scenarioOf(note(100, "a"), note(500, "b"));
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.of()), List.of());
        assertTrue(behaviour.scenarioWaveActive(), "wave active from boot");

        jumpTo(100);
        behaviour.onSimTick();
        assertTrue(behaviour.scenarioWaveActive(), "still one scripted event pending");

        // Mid-wave reload: suppression must survive (pending = events minus fired).
        ScriptedEventBehaviour rebuilt = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.copyOf(behaviour.firedEventIdsSorted())), List.of());
        assertTrue(rebuilt.scenarioWaveActive(), "suppression window survives a save/load");

        jumpTo(500);
        rebuilt.onSimTick();
        assertFalse(rebuilt.scenarioWaveActive(), "after the last scripted event, Poisson resumes");
    }

    @Test
    void sandboxHasNoWaveAndAFullyFiredScenarioReloadsInactive() {
        ScriptedEventBehaviour sandbox = new ScriptedEventBehaviour(null, ctx,
                ScenarioRuntime.sandbox(Set.of()), List.of());
        assertFalse(sandbox.scenarioWaveActive(), "sandbox never suppresses");

        Scenario s = scenarioOf(note(100, "only"));
        ScriptedEventBehaviour done = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.of("testsc#0")), List.of());
        assertFalse(done.scenarioWaveActive(), "a completed script reloads with no suppression");
    }

    // ---- the daily contracted stream ----

    private ServiceContract baseContract() {
        return AgentRoster.loadContracts().get("CONTRACT-1"); // cargo, berth_1, ETA 09:00
    }

    @Test
    void sandboxMaterialisesDayOneDailiesAndRegistersDayQualifiedClones() {
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                ScenarioRuntime.sandbox(Set.of()), List.of(baseContract()));
        jumpTo(6 * 3600); // 06:00 day 1 — before the 07:00 spawn moment
        behaviour.onSimTick();
        assertTrue(contracts.containsKey("CONTRACT-1-D1"),
                "the day-qualified clone is registered before its spawn event is due");
        assertEquals("C001-D1", contracts.get("CONTRACT-1-D1").vesselId());
        assertFalse(behaviour.firedEventIdsSorted().contains("contract:CONTRACT-1:d1"),
                "not yet fired — spawn moment is 07:00");

        jumpTo(8 * 3600); // past the spawn moment
        behaviour.onSimTick();
        assertTrue(behaviour.firedEventIdsSorted().contains("contract:CONTRACT-1:d1"),
                "the daily spawn fires at ETA − 2h");
    }

    @Test
    void scenarioModeSkipsDayOneDailiesAndStartsThemOnDayTwo() {
        Scenario s = scenarioOf(note(100, "script"));
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.of()), List.of(baseContract()));
        jumpTo(12 * 3600); // midday, day 1
        behaviour.onSimTick();
        assertFalse(contracts.containsKey("CONTRACT-1-D1"),
                "day 1 belongs to the script — no daily clone");

        jumpTo(86_400 + 6 * 3600); // 06:00, day 2
        behaviour.onSimTick();
        assertTrue(contracts.containsKey("CONTRACT-1-D2"), "dailies begin on day 2");
        assertFalse(contracts.containsKey("CONTRACT-1-D1"), "day 1 is never back-filled");
    }

    @Test
    void aFiredDailyIdWithAVesselTraceIsNeverReMaterialised() {
        // The normal reload: the day's arrival earned its fee (income trace) — the fired
        // id is skipped whole. The UNTRACED case is the recovery test further down.
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                ScenarioRuntime.sandbox(Set.of("contract:CONTRACT-1:d1"), Set.of("C001-D1")),
                List.of(baseContract()));
        jumpTo(12 * 3600); // past the day-1 spawn moment
        behaviour.onSimTick();
        assertFalse(contracts.containsKey("CONTRACT-1-D1"),
                "a fired, vouched-for daily id is skipped whole — no clone, no event, no respawn");
        assertEquals(List.of("contract:CONTRACT-1:d1"), behaviour.firedEventIdsSorted());
    }

    // ---- lost-spawn recovery (2026-07-18 adversarial-review finding 1) ----

    @Test
    void aFiredDailySpawnWithNoVesselTraceIsRecoveredOnReload() {
        // The save caught C001-D1 between spawn and grant: fired id persisted, vessel absent.
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                ScenarioRuntime.sandbox(Set.of("contract:CONTRACT-1:d1"), Set.of()),
                List.of(baseContract()));
        jumpTo(12 * 3600); // past the spawn moment
        behaviour.onSimTick();
        assertTrue(contracts.containsKey("CONTRACT-1-D1"),
                "the untraced daily spawn is re-materialised — the arrival is recovered");
        assertTrue(behaviour.firedEventIdsSorted().contains("contract:CONTRACT-1:d1"),
                "and re-fires exactly once in the restored timeline");
    }

    @Test
    void aFiredDailySpawnWithATraceStaysFired() {
        // The vessel earned its fee today (income trace) — recovery must NOT re-credit it.
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                ScenarioRuntime.sandbox(Set.of("contract:CONTRACT-1:d1"), Set.of("C001-D1")),
                List.of(baseContract()));
        jumpTo(12 * 3600);
        behaviour.onSimTick();
        assertFalse(contracts.containsKey("CONTRACT-1-D1"),
                "a vouched-for daily spawn is never re-materialised");
    }

    @Test
    void scenarioSpawnsAreNeverUnfiredEvenWithoutATrace() {
        // Deliberate scope limit (see unfireLostSpawns javadoc): a scripted spawn's
        // pre-grant window is wall-milliseconds (eta = now), while un-firing one after a
        // cross-midnight save would re-credit a settled day's fee through the rebuilt
        // ledger; and dropped walk-ins must stay dropped (task 22's drop semantics).
        contracts.put("T-CON", new ServiceContract("T-CON", "TVESSEL", "cargo_vessel",
                "berth_1", 1_000.0, 4, 0L));
        Scenario s = scenarioOf(
                new it.unige.portcommand.scenario.events.SpawnVesselEvent(100, "cargo_vessel",
                        "contracted", null, "T-CON", null, null, null, null),
                new it.unige.portcommand.scenario.events.SpawnVesselEvent(200, "cargo_vessel",
                        "walk_in", "W1", null, null, null, null, null));
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                new ScenarioRuntime(s, Set.of("testsc#0", "testsc#1"), Set.of()), List.of());
        assertFalse(behaviour.scenarioWaveActive(), "both stay fired — nothing re-arms");
        jumpTo(300);
        behaviour.onSimTick();
        assertEquals(List.of("testsc#0", "testsc#1"), behaviour.firedEventIdsSorted());
    }

    @Test
    void bootingOnALaterDayMaterialisesOnlyThatDayForward() {
        clock.restore((2 * 86_400L + 6 * 3600L) * 1000L, 300); // 06:00, day 3
        clock.resume();
        ScriptedEventBehaviour behaviour = new ScriptedEventBehaviour(null, ctx,
                ScenarioRuntime.sandbox(Set.of()), List.of(baseContract()));
        behaviour.onSimTick();
        assertTrue(contracts.containsKey("CONTRACT-1-D3"));
        assertFalse(contracts.containsKey("CONTRACT-1-D1"), "past days are dead history");
        assertFalse(contracts.containsKey("CONTRACT-1-D2"), "no late-arrival burst on load");
    }
}
