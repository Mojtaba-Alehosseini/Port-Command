package it.unige.portcommand.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.artifacts.DealRecord;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.harbourmaster.ExpenseEvent;
import it.unige.portcommand.harbourmaster.IncomeEvent;
import it.unige.portcommand.ontology.Deal;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.persistence.dto.CustomsStateDTO;
import it.unige.portcommand.persistence.dto.HarbourMasterStateDTO;
import it.unige.portcommand.persistence.dto.TerminalStateDTO;
import it.unige.portcommand.persistence.dto.TugStateDTO;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import it.unige.portcommand.persistence.dto.WeatherStateDTO;
import it.unige.portcommand.agents.WeatherSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 22: save → load → equals, then re-save → byte-identical (the LinkedHashMap /
 * sorted-collections determinism rule made observable). {@code savedAt} is pinned, so
 * the diff needs no exclusions. Both directions go through the ONE production mapper
 * ({@code SaveLoadManager.mapper()}) — planning/22's "same ObjectMapper" note.
 */
class SaveLoadManagerRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void saveLoadRoundTripPreservesEverythingAndReSaveIsByteIdentical() throws Exception {
        SaveLoadManager manager = new SaveLoadManager(tempDir);
        GameState original = representativeState();

        Path first = manager.save(tempDir.resolve("savegame.json"), original);
        GameState loaded = manager.load(first);
        assertEquals(original, loaded, "record equality must survive the round trip");

        Path second = manager.save(tempDir.resolve("resaved.json"), loaded);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "identical state must serialise to identical bytes (determinism rule)");
    }

    @Test
    void otherChannelFieldsStayNullThroughTheRoundTrip() throws Exception {
        SaveLoadManager manager = new SaveLoadManager(tempDir);
        Path file = manager.save(tempDir.resolve("savegame.json"), representativeState());
        GameState loaded = manager.load(file);

        VesselStateDTO contracted = loaded.agents().activeVessels().get(0);
        assertEquals("contracted", contracted.channel());
        assertNull(contracted.personality(), "walk-in beliefs must be null on a contracted vessel");
        assertNull(contracted.minAcceptablePrice());

        VesselStateDTO walkIn = loaded.agents().activeVessels().get(1);
        assertEquals("walk_in", walkIn.channel());
        assertNull(walkIn.contractRef(), "contracted fields must be null on a walk-in");
        assertEquals(2100.0, walkIn.minAcceptablePrice(), "hidden beliefs persist verbatim");
        // The other-channel nulls are ABSENT from the file, not serialized as null (NON_NULL).
        String json = Files.readString(file);
        assertTrue(json.contains("\"minAcceptablePrice\""), "walk-in beliefs are in the file");
    }

    @Test
    void minimalHandCraftedFixtureBinds() throws Exception {
        Path fixture = fixture("v1_minimal.json");
        GameState state = new SaveLoadManager(tempDir).load(fixture);
        assertEquals(1, state.schemaVersion());
        assertEquals(50_000.0, state.wallet());
        assertEquals(70, state.reputation());
        assertEquals(4, state.agents().tugs().size());
        assertEquals(2, state.agents().terminals().size());
        assertTrue(state.agents().activeVessels().isEmpty());
    }

    static Path fixture(String name) throws Exception {
        return Path.of(SaveLoadManagerRoundTripTest.class.getResource("/savefixtures/" + name).toURI());
    }

    /** One of everything: both vessel channels, live tug job, occupied berth, day money. */
    static GameState representativeState() {
        VesselStateDTO contracted = new VesselStateDTO(
                "C001", "cargo_vessel", VesselStateDTO.CHANNEL_CONTRACTED,
                9.5, 180.0, 40_000, "general_cargo", 0L,
                "IN_TRANSIT", "TRANSIT", 3_720_000L,
                "berth_1", 5_200.0, 8,
                List.of("tug_1"), false, false,
                700.0, 200.0, 180.0,
                "CONTRACT-1",
                null, null, null, null, null, null, null);
        VesselStateDTO walkIn = new VesselStateDTO(
                "WALKIN-1", "tanker", VesselStateDTO.CHANNEL_WALK_IN,
                11.0, 220.0, 60_000, "general_cargo", 600_000L,
                "AWAITING_TUG", "DOCKED", 32_400_000L,
                "berth_3", 2_450.0, 9,
                List.of(), false, true,
                420.0, 320.0, 90.0,
                null,
                "AGGRESSIVE", 2_100.0, 2_800.0, 45, 6, 3, 1_200_000L);
        Map<String, Integer> performatives = new LinkedHashMap<>();
        performatives.put("REQUEST", 2);
        performatives.put("INFORM", 7);
        Map<String, String> pendingCustoms = new LinkedHashMap<>();
        pendingCustoms.put("cust-C001", "C001");
        return new GameState(
                SaveLoadManager.SCHEMA_VERSION,
                "2026-07-17T12:00:00Z",
                987_654_321L,
                Settings.defaults(),
                new GameState.SimClockState(7_200_000L, 300L),
                47_350.0,
                72,
                1,
                2,
                List.of(new DealRecord("tanker", 9, 2_450.0, 6_000_000L, Deal.Outcome.DEAL)),
                List.of("auto accept if price > 2200 for tankers"),
                // Task 23: a mid-scenario save — the replay guard + scenario marker round-trip.
                "tutorial",
                List.of("contract:CONTRACT-1:d1", "tutorial#0", "tutorial#2"),
                new GameState.AgentsState(
                        new HarbourMasterStateDTO(
                                List.of(new IncomeEvent(2_450.0, "berth_base", "WALKIN-1", 6_000_000L)),
                                List.of(new ExpenseEvent(350.0, "tug_job", "C001", 6_100_000L)),
                                performatives,
                                pendingCustoms,
                                1),
                        new WeatherStateDTO(new WeatherSnapshot(22, "good", 0.8, "cloudy", 7_000_000L)),
                        new CustomsStateDTO(3),
                        List.of(
                                new TugStateDTO("tug_1", "ESCORTING", new Position(400.0, 300.0, 45.0), 0.92,
                                        new TugStateDTO.TugJobDTO("C001", new Position(500.0, 350.0, 0.0),
                                                "cnp-test-1"),
                                        null),
                                new TugStateDTO("tug_2", "IDLE", new Position(60.0, 120.0, 0.0), 1.0, null, null),
                                new TugStateDTO("tug_3", "IDLE", new Position(60.0, 180.0, 0.0), 1.0, null, null),
                                new TugStateDTO("tug_4", "RETURNING", new Position(200.0, 100.0, 10.0), 0.7,
                                        null, null)),
                        List.of(
                                new TerminalStateDTO("terminal_container", List.of()),
                                new TerminalStateDTO("terminal_general", List.of(
                                        new BerthOccupancy("berth_1", "C001", 7_000_000L, 35_800_000L, 1,
                                                BerthOccupancy.Status.PROVISIONAL)))),
                        List.of(contracted, walkIn)));
    }
}
