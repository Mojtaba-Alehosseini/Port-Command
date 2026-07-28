package it.unige.portcommand.persistence;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 22: truncated JSON, wrong types, schema-invariant violations and a missing file all
 * surface as ONE clean {@link CorruptSaveException} — never a stack-trace-shaped half-boot.
 */
class CorruptSaveTest {

    @TempDir
    Path tempDir;

    private SaveLoadManager manager() {
        return new SaveLoadManager(tempDir);
    }

    @Test
    void truncatedFileIsCorrupt() {
        assertThrows(CorruptSaveException.class,
                () -> manager().load(SaveLoadManagerRoundTripTest.fixture("corrupt_truncated.json")));
    }

    @Test
    void missingFileIsCorruptNotFileNotFound() {
        CorruptSaveException e = assertThrows(CorruptSaveException.class,
                () -> manager().load(tempDir.resolve("nope.json")));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void wrongTypesAreCorrupt() throws Exception {
        Path file = tempDir.resolve("bad-types.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"wallet\":\"lots\"}");
        assertThrows(CorruptSaveException.class, () -> manager().load(file));
    }

    @Test
    void unknownVesselTypeIsCorrupt_neverPleasure() throws Exception {
        Path file = rewriteMinimal("\"activeVessels\" : [ ]",
                vesselWith("\"vesselType\" : \"pleasure\"", "\"channel\" : \"walk_in\""));
        CorruptSaveException e = assertThrows(CorruptSaveException.class, () -> manager().load(file));
        assertTrue(e.getMessage().contains("canonical"), "the 5-types rule is named in the message");
    }

    @Test
    void unknownChannelIsCorrupt() throws Exception {
        Path file = rewriteMinimal("\"activeVessels\" : [ ]",
                vesselWith("\"vesselType\" : \"tanker\"", "\"channel\" : \"chartered\""));
        assertThrows(CorruptSaveException.class, () -> manager().load(file));
    }

    @Test
    void wrongTugCountIsCorrupt() throws Exception {
        // Drop tug_4 by renaming it away is not enough (the set check would catch it) — remove
        // the whole object. Guarded replace (review #9): a silent no-op must fail the test.
        String find = ", {\n      \"tugId\" : \"tug_4\",\n"
                + "      \"status\" : \"IDLE\",\n"
                + "      \"position\" : { \"x\" : 60.0, \"y\" : 240.0, \"heading_deg\" : 0.0 },\n"
                + "      \"fuelState\" : 1.0\n    } ]";
        Path file = rewriteMinimal(find, " ]");
        CorruptSaveException e = assertThrows(CorruptSaveException.class, () -> manager().load(file));
        assertTrue(e.getMessage().contains("4 tugs"));
    }

    @Test
    void wrongTugIdsAreCorruptEvenAtTheRightCount() throws Exception {
        Path file = rewriteMinimal("\"tugId\" : \"tug_4\"", "\"tugId\" : \"tug_5\"");
        CorruptSaveException e = assertThrows(CorruptSaveException.class, () -> manager().load(file));
        assertTrue(e.getMessage().contains("tug_1..tug_4"));
    }

    /**
     * Vessel JSON with every required flow field present, so ONLY the field under test can
     * fail validation. Deserialization runs first; validation second.
     */
    private static String vesselWith(String typeField, String channelField) {
        return "\"activeVessels\" : [ {\n"
                + "  \"id\" : \"V-BAD\",\n"
                + "  " + typeField + ",\n"
                + "  " + channelField + ",\n"
                + "  \"draft\" : 9.0, \"length\" : 150.0, \"tonnage\" : 30000,\n"
                + "  \"cargoClass\" : \"general_cargo\", \"etaArrivalSimMillis\" : 0,\n"
                + "  \"stage\" : \"IN_TRANSIT\", \"currentPhase\" : \"TRANSIT\",\n"
                + "  \"phaseDeadlineSimMillis\" : 1000,\n"
                + "  \"assignedBerth\" : \"berth_1\", \"dealPrice\" : 1000.0, \"dealHours\" : 5,\n"
                + "  \"assignedTugs\" : [ ], \"blocked\" : false, \"weatherHeld\" : false,\n"
                + "  \"posX\" : 0.0, \"posY\" : 0.0, \"posHeading\" : 0.0\n"
                + "} ]";
    }

    private Path rewriteMinimal(String find, String replace) throws Exception {
        String minimal = Files.readString(SaveLoadManagerRoundTripTest.fixture("v1_minimal.json"));
        assertTrue(minimal.contains(find), "fixture drifted — expected marker not found");
        Path file = tempDir.resolve("rewritten.json");
        Files.writeString(file, minimal.replace(find, replace));
        return file;
    }
}
