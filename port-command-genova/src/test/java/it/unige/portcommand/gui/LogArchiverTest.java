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
