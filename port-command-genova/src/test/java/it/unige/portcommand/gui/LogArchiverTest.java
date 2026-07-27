package it.unige.portcommand.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.gui.events.CommLogEvent;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogArchiverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void archiveWritesOneValidJsonLinePerCallToTodaysDatedFile(@TempDir Path tempDir) throws Exception {
        LogArchiver archiver = new LogArchiver(tempDir);
        archiver.archive(new CommLogEvent(0L, "hm", List.of("tug_1", "tug_2"), ACLMessage.CFP, "first", "c1"));
        archiver.archive(new CommLogEvent(1000L, "tug_1", List.of("hm"), ACLMessage.PROPOSE, "second", "c2"));

        // Filename carries today's REAL-clock ISO date (log-file convention, not sim time).
        Path archive = tempDir.resolve("commlog-archive-" + LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(archive), "archive file named with today's ISO date must exist");

        List<String> lines = Files.readAllLines(archive);
        assertEquals(2, lines.size(), "exactly one line per archive() call");

        JsonNode first = MAPPER.readTree(lines.get(0)); // each line must be standalone valid JSON
        assertEquals("hm", first.get("sender").asText());
        assertEquals("first", first.get("paraphrase").asText());
        assertEquals(ACLMessage.CFP, first.get("performative").asInt());
        assertEquals(2, first.get("receivers").size(), "multi-receiver list round-trips");

        JsonNode second = MAPPER.readTree(lines.get(1));
        assertEquals("second", second.get("paraphrase").asText());
        assertEquals("tug_1", second.get("sender").asText());
    }

    /**
     * Audit C-06 (2026-07-27). {@code CommLogModel.clear()} used to call {@link LogArchiver#archive}
     * once per entry — up to 500 sequential {@code createDirectories} + open + append + close calls
     * <b>on the EDT</b>, on every Load and every New Game, which is precisely what DEMO_SCRIPT
     * Beat 5's scenario switch does. {@code archiveAll} pays one file round-trip instead of N.
     * The single-syscall property itself is structural (one {@code Files.writeString} in the
     * method); what is asserted here is that batching did not cost ordering or completeness.
     */
    @Test
    void archiveAllWritesEveryEntryInOrderInOneGo(@TempDir Path tempDir) throws Exception {
        LogArchiver archiver = new LogArchiver(tempDir);
        archiver.archiveAll(List.of(
                new CommLogEvent(0L, "hm", List.of("tug_1"), ACLMessage.CFP, "one", "c1"),
                new CommLogEvent(1L, "hm", List.of("tug_2"), ACLMessage.CFP, "two", "c2"),
                new CommLogEvent(2L, "hm", List.of("tug_3"), ACLMessage.CFP, "three", "c3")));

        List<String> lines = Files.readAllLines(tempDir.resolve("commlog-archive-" + LocalDate.now() + ".jsonl"));
        assertEquals(3, lines.size(), "one line per event, batched or not");
        assertEquals("one", MAPPER.readTree(lines.get(0)).get("paraphrase").asText());
        assertEquals("two", MAPPER.readTree(lines.get(1)).get("paraphrase").asText());
        assertEquals("three", MAPPER.readTree(lines.get(2)).get("paraphrase").asText(),
                "batching must not reorder — the archive is the history's tail");
    }

    @Test
    void archiveAllOnAnEmptyBatchWritesNothingAndDoesNotCreateTheDirectory(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("never-created");
        new LogArchiver(missing).archiveAll(List.of());

        assertFalse(Files.exists(missing), "an empty clear() must not touch the disk at all");
    }

    @Test
    void archiveAllAppendsRatherThanReplacing(@TempDir Path tempDir) throws Exception {
        LogArchiver archiver = new LogArchiver(tempDir);
        archiver.archive(new CommLogEvent(0L, "hm", List.of("t"), ACLMessage.CFP, "evicted", "c0"));
        archiver.archiveAll(List.of(new CommLogEvent(1L, "hm", List.of("t"), ACLMessage.CFP, "cleared", "c1")));

        List<String> lines = Files.readAllLines(tempDir.resolve("commlog-archive-" + LocalDate.now() + ".jsonl"));
        assertEquals(2, lines.size(), "a batched clear must not truncate the eviction history before it");
        assertEquals("evicted", MAPPER.readTree(lines.get(0)).get("paraphrase").asText());
    }

    @Test
    void archiveCreatesTheTargetDirectoryTreeIfItIsMissing(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("nested/does/not/exist/yet");
        LogArchiver archiver = new LogArchiver(missing);

        archiver.archive(new CommLogEvent(0L, "hm", List.of("v1"), ACLMessage.INFORM, "x", "c1"));

        Path archive = missing.resolve("commlog-archive-" + LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(archive), "archive() must create the target directory tree on demand");
        assertEquals(1, Files.readAllLines(archive).size());
    }
}
