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

    private static Scenario scenarioOf(ScriptedEvent... events) {
        return new Scenario("testsc", "Test", "test scenario", 1_000.0, 7L, null,
                new Scenario.InitialState(1, "08:00", 1_000.0, 50,
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
