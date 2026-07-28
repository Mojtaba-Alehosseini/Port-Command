package it.unige.portcommand.scenario;

import java.nio.file.Path;
import java.util.List;

import it.unige.portcommand.persistence.GameState;
import it.unige.portcommand.persistence.GameStateBuilder;
import it.unige.portcommand.persistence.SaveLoadManager;
import it.unige.portcommand.persistence.dto.TerminalStateDTO;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GameStateBuilder.fromScenario} produces a state that IS save-shaped (task 23's
 * central reconciliation): it passes the task-22 invariant gate, round-trips through
 * {@code SaveLoadManager} byte-identically, and carries the §3.15 opening numbers.
 */
class GameStateFromScenarioTest {

    private final ScenarioRegistry registry = new ScenarioRegistry();

    @TempDir
    Path tempDir;

    @Test
    void everyPackagedScenarioBuildsAValidSaveShapedState() {
        SaveLoadManager manager = new SaveLoadManager(tempDir);
        for (Scenario s : registry.all()) {
            GameState state = GameStateBuilder.fromScenario(s, 123L, 900L, "2026-07-18T10:00:00Z");
            assertDoesNotThrow(() -> manager.validateState(state, "scenario:" + s.key()),
                    s.key() + " must pass the SAME invariant gate a save file passes");
            assertEquals(1, state.schemaVersion(), "schemaVersion STAYS 1 — the fields are additive");
            assertEquals(s.key(), state.activeScenario());
            assertEquals(List.of(), state.firedEventIds(), "fresh scenario — nothing fired yet");
        }
    }

    @Test
    void scenarioStateRoundTripsThroughTheRealSaveFile() throws Exception {
        SaveLoadManager manager = new SaveLoadManager(tempDir);
        Scenario storm = registry.findByKey("storm").orElseThrow();
        GameState built = GameStateBuilder.fromScenario(storm, 0L, 900L, "2026-07-18T10:00:00Z");
        Path file = manager.save(tempDir.resolve("scenario-boot.json"), built);
        GameState reloaded = manager.load(file);
        assertEquals(built, reloaded, "fromScenario output survives the real save/load unchanged");
        assertEquals(0L, reloaded.randomSeed(), "seed 0 is a valid pin and persists as itself");
    }

    @Test
    void firedEventIdsIsAlwaysSerializedEvenWhenEmpty() throws Exception {
        SaveLoadManager manager = new SaveLoadManager(tempDir);
        GameState built = GameStateBuilder.fromScenario(registry.findByKey("tutorial").orElseThrow(),
                20260101L, 1800L, "2026-07-18T10:00:00Z");
        Path file = manager.save(tempDir.resolve("tutorial-boot.json"), built);
        String json = java.nio.file.Files.readString(file);
        assertTrue(json.contains("\"firedEventIds\" : [ ]"),
                "an empty firedEventIds is serialized as [], never omitted (NON_NULL cannot drop it)");
    }

    @Test
    void tutorialOpensOnTheCanonicalStartState() {
        GameState state = GameStateBuilder.fromScenario(registry.findByKey("tutorial").orElseThrow(),
                20260101L, 1800L, "t");
        assertEquals(15_000.0, state.wallet());
        assertEquals(50, state.reputation());
        assertEquals(8 * 3_600_000L, state.simClock().simMillis(), "Day 1, 08:00");
        assertEquals(1800L, state.simClock().realSecondsPerGameDay());
        assertEquals("EASY", state.settings().difficulty());
        assertEquals(8, state.agents().weather().current().wind());
        assertEquals(0, state.agents().activeVessels().size(), "all berths free");
    }

    @Test
    void stormDerivesDockedVesselsAndOccupanciesTogether() {
        GameState state = GameStateBuilder.fromScenario(registry.findByKey("storm").orElseThrow(),
                20260320L, 900L, "t");
        assertEquals(30_000.0, state.wallet());
        assertEquals(70, state.reputation());

        List<VesselStateDTO> docked = state.agents().activeVessels();
        assertEquals(2, docked.size(), "2 pre-existing docked cargo vessels");
        assertTrue(docked.stream().allMatch(v -> "DOCKED".equals(v.currentPhase())
                        && "DOCKED".equals(v.stage()) && VesselStateDTO.CHANNEL_WALK_IN.equals(v.channel())),
                "docked walk-ins whose deal closed pre-scenario");

        // Occupancy and vessel derive together, so reconcileTerminals must keep both DOCKED
        // (an id mismatch would silently free the berth at load — the ghost-berth rule).
        List<TerminalStateDTO> reconciled = GameStateBuilder.reconcileTerminals(state);
        long dockedBerths = reconciled.stream()
                .flatMap(t -> t.occupancies().stream())
                .filter(o -> o.status() == it.unige.portcommand.agents.BerthOccupancy.Status.DOCKED)
                .count();
        assertEquals(2, dockedBerths, "both scenario occupancies survive reconciliation");

        // Reconciled layout (dated 2026-07-18): berth_1 must stay free — it is the only
        // tanker-capable berth and ST-901 arrives at 0s.
        assertTrue(reconciled.stream()
                        .flatMap(t -> t.occupancies().stream())
                        .filter(o -> "berth_1".equals(o.berthId()))
                        .allMatch(o -> o.status() == it.unige.portcommand.agents.BerthOccupancy.Status.FREE),
                "berth_1 free for the storm tanker");
    }

    @Test
    void busyDayBootsTugOneRefuellingWithLowTank() {
        GameState state = GameStateBuilder.fromScenario(registry.findByKey("busy_day").orElseThrow(),
                20260214L, 900L, "t");
        assertEquals(25_000.0, state.wallet());
        assertEquals(60, state.reputation());
        var tug1 = state.agents().tugs().stream()
                .filter(t -> "tug_1".equals(t.tugId())).findFirst().orElseThrow();
        // The task-22 restore rule normalises REFUELING → IDLE-with-low-tank and
        // RefuelIfLowBehaviour re-triggers; the DTO carries the raw REFUELING status.
        assertEquals("REFUELING", tug1.status());
        assertEquals(0.05, tug1.fuelState());
    }
}
