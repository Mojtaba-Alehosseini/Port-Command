package it.unige.portcommand.persistence;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 22 hard constraint: unknown {@code schemaVersion} is a hard, messaged refusal. */
class SchemaVersionTest {

    @TempDir
    Path tempDir;

    @Test
    void futureVersionIsRefusedWithItsVersionNumber() throws Exception {
        SchemaVersionException e = assertThrows(SchemaVersionException.class,
                () -> new SaveLoadManager(tempDir)
                        .load(SaveLoadManagerRoundTripTest.fixture("v2_future.json")));
        assertEquals(2, e.foundVersion());
        assertTrue(e.getMessage().contains("please start a new game"),
                "the user-facing message ships in the exception verbatim");
    }

    @Test
    void missingSchemaVersionIsRefusedNotDefaulted() throws Exception {
        Path file = tempDir.resolve("no-version.json");
        Files.writeString(file, "{\"savedAt\":\"2026-07-17T00:00:00Z\"}");
        SchemaVersionException e = assertThrows(SchemaVersionException.class,
                () -> new SaveLoadManager(tempDir).load(file));
        assertEquals(-1, e.foundVersion());
    }
}
