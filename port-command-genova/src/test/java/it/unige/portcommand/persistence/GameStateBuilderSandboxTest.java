package it.unige.portcommand.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.persistence.dto.HarbourMasterStateDTO;
import it.unige.portcommand.persistence.dto.TerminalStateDTO;
import it.unige.portcommand.persistence.dto.TugStateDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): {@code GameStateBuilder.freshSandbox} — the opening state of a Free play
 * game — IS save-shaped. That is the whole point of it: {@code GameSession.startSandbox} boots it
 * through the task-22 load path, so it must pass the invariant gate a save file passes, survive
 * the real save/load unchanged, and carry exactly the figures the directly-spawned sandbox
 * ({@code GameSession.startNewGame}) starts from. The scenario counterpart is
 * {@code scenario.GameStateFromScenarioTest}; the live boot is {@link FreePlaySessionIT}.
 */
class GameStateBuilderSandboxTest {

    private static final long SEED = 42L;
    private static final long DAY_LENGTH = 3_600L;
    private static final String SAVED_AT = "2026-10-12T09:00:00Z";

    @TempDir
    Path tempDir;

    @Test
    void freshSandboxPassesTheSameInvariantGateASaveFilePasses() {
        SaveLoadManager manager = new SaveLoadManager(tempDir);
        GameState state = GameStateBuilder.freshSandbox(SEED, DAY_LENGTH, SAVED_AT);

        assertDoesNotThrow(() -> manager.validateState(state, "sandbox"),
                "the Free play opening state must pass the SAME gate a save file passes — it is "
                        + "validated BEFORE the running world is torn down");
        assertEquals(SaveLoadManager.SCHEMA_VERSION, state.schemaVersion(),
                "schemaVersion STAYS 1 — nothing about free play is a new save shape");
    }

    @Test
    void freshSandboxRoundTripsThroughTheRealSaveFileAndReSavesByteIdentically() throws Exception {
        SaveLoadManager manager = new SaveLoadManager(tempDir);
        GameState built = GameStateBuilder.freshSandbox(SEED, DAY_LENGTH, SAVED_AT);

        Path first = manager.save(tempDir.resolve("free-play-boot.json"), built);
        GameState reloaded = manager.load(first);
        assertEquals(built, reloaded, "freshSandbox output survives the real save/load unchanged");

        Path second = manager.save(tempDir.resolve("free-play-resaved.json"), reloaded);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "identical state must serialise to identical bytes (determinism rule)");

        // The file is a SANDBOX save: no scenario marker at all (NON_NULL omits the null key, and
        // a load without it resumes with sandbox semantics), and the replay guard present as [].
        String json = Files.readString(first);
        assertFalse(json.contains("\"activeScenario\""),
                "a Free play state names no scenario — the key must be absent from the file");
        assertTrue(json.contains("\"firedEventIds\" : [ ]"),
                "an empty firedEventIds is serialized as [], never omitted");
    }

    @Test
    void freshSandboxOpensOnTheSandboxStartingFigures() {
        GameState state = GameStateBuilder.freshSandbox(SEED, DAY_LENGTH, SAVED_AT);

        assertEquals(SAVED_AT, state.savedAt());
        assertEquals(SEED, state.randomSeed(), "the game's seed is recorded, hence in every save");

        // Settings: the classpath baseline with the day length the clock runs at — nothing else.
        assertEquals(Settings.classpathDefaults().withRealSecondsPerGameDay(DAY_LENGTH), state.settings(),
                "settings = classpath defaults + the boot pace (never the live Settings.load())");
        assertEquals("NORMAL", state.settings().difficulty(), "no scenario override: default difficulty");
        assertEquals(1, state.settings().gameSpeed(), "a new game starts at ×1");

        // Clock: Day 1, 00:00, and the settings block agrees with it about the pace.
        assertEquals(0L, state.simClock().simMillis(), "Day 1, 00:00");
        assertEquals(DAY_LENGTH, state.simClock().realSecondsPerGameDay());
        assertEquals(state.simClock().realSecondsPerGameDay(), state.settings().realSecondsPerGameDay(),
                "the settings' day length matches the clock's from the first save on");

        // Money and standing: the SAME constants startNewGame() feeds the HarbourMaster.
        assertEquals(AgentRoster.STARTING_WALLET, state.wallet());
        assertEquals(50_000.0, state.wallet(), "€50,000 — the figure the New Game dialog advertises");
        assertEquals((int) AgentRoster.STARTING_REPUTATION, state.reputation());
        assertEquals(70, state.reputation());
        assertEquals(0, state.consecutiveDeepDebtDays(), "no bankruptcy streak");
        assertEquals(0, state.lastDeepDebtDay());

        // Nothing inherited from a previous world, and no scenario.
        assertEquals(List.of(), state.marketHistory(), "empty market history");
        assertEquals(List.of(), state.policyTriggers(), "no autopilot policies");
        assertNull(state.activeScenario(), "free play runs no scenario");
        assertEquals(List.of(), state.firedEventIds(), "nothing fired — the daily stream starts at day 1");
    }

    @Test
    void freshSandboxCarriesAnEmptyHarbourMasterAndTheFixedFleetAtRest() {
        GameState.AgentsState agents = GameStateBuilder.freshSandbox(SEED, DAY_LENGTH, SAVED_AT).agents();

        HarbourMasterStateDTO hm = agents.harbourMaster();
        assertEquals(List.of(), hm.dayIncome(), "no income yet today");
        assertEquals(List.of(), hm.dayExpense(), "no expense yet today");
        assertTrue(hm.performativeCounts().isEmpty(), "an empty performative tally");
        assertTrue(hm.pendingCustomsChecks().isEmpty(), "no customs conversation in flight");
        assertEquals(0, hm.walkInSpawnCount(), "walk-in ids start at WALKIN-1");
        assertEquals(-1, hm.namePoolOffset(),
                "the 'roll fresh at boot' sentinel — the HarbourMaster draws the offset from the seed");

        assertEquals(AgentRoster.defaultWeather(), agents.weather().current(),
                "the calm reading startNewGame() boots under — one source of truth");
        assertEquals("sunny", agents.weather().current().state(), "free play opens in calm weather");
        assertEquals(1, agents.customs().nextClearanceRef(), "clearance refs start at CL-1");

        assertEquals(List.of("tug_1", "tug_2", "tug_3", "tug_4"),
                agents.tugs().stream().map(TugStateDTO::tugId).toList(), "the four tugs, in id order");
        for (TugStateDTO tug : agents.tugs()) {
            assertEquals("IDLE", tug.status(), tug.tugId() + " is idle");
            assertEquals(1.0, tug.fuelState(), tug.tugId() + " has a full tank");
            assertNull(tug.position(), tug.tugId() + " has no saved position — it restores to its base");
            assertNull(tug.job(), tug.tugId() + " has no job");
            assertNull(tug.pickupTarget(), tug.tugId() + " has no pickup target");
        }

        assertEquals(List.of("terminal_container", "terminal_general"),
                agents.terminals().stream().map(TerminalStateDTO::terminalId).toList());
        assertEquals(List.of(BerthOccupancy.free("berth_2")), agents.terminals().get(0).occupancies(),
                "terminal_container manages berth_2");
        assertEquals(List.of(BerthOccupancy.free("berth_1"), BerthOccupancy.free("berth_3"),
                        BerthOccupancy.free("berth_4")), agents.terminals().get(1).occupancies(),
                "terminal_general manages berths 1, 3 and 4");

        assertEquals(List.of(), agents.activeVessels(), "no vessels in port");
    }

    @Test
    void reconcileTerminalsLeavesFourFreeBerths() {
        GameState state = GameStateBuilder.freshSandbox(SEED, DAY_LENGTH, SAVED_AT);

        // What bootWorld hands the terminals: with no vessels there is nothing to reconcile away,
        // and the four FREE records must all survive — the terminals push them to the session's
        // PortStateArtifact, which is what erases a REPLACED world's bookings.
        List<BerthOccupancy> occupancies = GameStateBuilder.reconcileTerminals(state).stream()
                .flatMap(terminal -> terminal.occupancies().stream())
                .toList();

        assertEquals(4, occupancies.size(), "all four berths are stated, none omitted");
        assertEquals(List.of("berth_1", "berth_2", "berth_3", "berth_4"),
                occupancies.stream().map(BerthOccupancy::berthId).sorted().toList());
        assertTrue(occupancies.stream().allMatch(o -> o.status() == BerthOccupancy.Status.FREE
                        && o.vesselId() == null),
                "every berth is FREE with no occupant, got " + occupancies);
    }

    @Test
    void seedAndDayLengthAreTheCallersAndTheBuilderIsDeterministic() {
        // Any long is a valid seed: 0 is a pin, never a sentinel, and Random.nextLong() is negative
        // half the time.
        assertEquals(0L, GameStateBuilder.freshSandbox(0L, DAY_LENGTH, SAVED_AT).randomSeed());
        assertEquals(-7L, GameStateBuilder.freshSandbox(-7L, DAY_LENGTH, SAVED_AT).randomSeed());

        // An explicit --day-length reaches both the clock and the settings block.
        GameState fast = GameStateBuilder.freshSandbox(SEED, 5L, SAVED_AT);
        assertEquals(5L, fast.simClock().realSecondsPerGameDay());
        assertEquals(5L, fast.settings().realSecondsPerGameDay());

        assertEquals(GameStateBuilder.freshSandbox(SEED, DAY_LENGTH, SAVED_AT),
                GameStateBuilder.freshSandbox(SEED, DAY_LENGTH, SAVED_AT),
                "same arguments, same state — nothing random or clock-derived is baked in");
    }

    @Test
    void theBuilderDoesNotReadTheLiveSettings() {
        // Settings.load() is the session's live record: after a scenario or a loaded save it holds
        // THAT world's applied block. A Free play game must start from the baseline regardless.
        Settings before = Settings.load();
        try {
            Settings.applyLive(Settings.classpathDefaults()
                    .withDifficulty("HARD").withRoundLimit(7).withGameSpeed(4).withAutopilotEnabled(true));

            GameState state = GameStateBuilder.freshSandbox(SEED, DAY_LENGTH, SAVED_AT);

            assertEquals(Settings.classpathDefaults().withRealSecondsPerGameDay(DAY_LENGTH), state.settings(),
                    "a previous world's applied settings must not leak into a new Free play game");
        } finally {
            Settings.applyLive(before); // JVM-wide volatile — never leak into other suites
        }
    }

    @Test
    void aNonPositiveDayLengthIsRefusedByTheGateNotAtBoot() {
        SaveLoadManager manager = new SaveLoadManager(tempDir);
        GameState broken = GameStateBuilder.freshSandbox(SEED, 0L, SAVED_AT);

        assertThrows(CorruptSaveException.class, () -> manager.validateState(broken, "sandbox"),
                "a state the clock could not adopt must fail the gate — startSandbox validates "
                        + "before it tears the running world down");
    }
}
