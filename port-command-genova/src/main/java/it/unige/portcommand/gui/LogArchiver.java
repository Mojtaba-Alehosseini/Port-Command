package it.unige.portcommand.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.gui.events.CommLogEvent;

/**
 * Append-only disk archive for {@link CommLogEvent}s that fall off the tail of
 * {@link it.unige.portcommand.gui.model.CommLogModel}'s bounded in-memory buffer. The model keeps
 * only its {@link it.unige.portcommand.gui.model.CommLogModel#MAX_ENTRIES visible cap}; this class
 * is where the oldest entries go before they are discarded, so a long play session's full comm
 * history survives on disk even though the panel only shows a window of it.
 *
 * <p>One {@link CommLogEvent} is written per line as JSON ({@code .jsonl}) via the project's
 * {@link TerminalJson}. The file is named {@code commlog-archive-{date}.jsonl} using
 * {@link LocalDate#now()} — the REAL wall-clock date. This is an ordinary rolling-log naming
 * convention (which calendar day the archive was written), NOT the simulation clock, so the
 * {@code SimClock}-must-never-read-the-real-clock rule does not apply here.
 *
 * <p>Resilience: {@link #archive} never throws. Archiving runs on the EDT (it is driven from the
 * model's eviction path, which the panel touches on the EDT), and a disk hiccup must never crash
 * the UI thread — {@link IOException} is caught, logged, and swallowed.
 */
public final class LogArchiver {

    private static final Logger log = LoggerFactory.getLogger(LogArchiver.class);

    private static final Path DEFAULT_DIR = Path.of("logs");

    private final Path targetDir;

    /** Production: archives under {@code logs/} (git-ignored, alongside the logback output). */
    public LogArchiver() {
        this(DEFAULT_DIR);
    }

    /**
     * Test seam: points the archive at an arbitrary directory (e.g. a JUnit {@code @TempDir}) so the
     * fast test lane never writes to the real {@code logs/} folder.
     *
     * <p>Deliberately {@code public}, not package-private: the injection point that consumes it —
     * {@code CommLogModel(LogArchiver)} — lives in the sibling package {@code gui.model}, and its
     * test ({@code CommLogModelTest}) must be able to construct a temp-dir archiver to inject. A
     * package-private overload would be unreachable from {@code gui.model}.
     */
    public LogArchiver(Path targetDir) {
        this.targetDir = targetDir;
    }

    /**
     * Appends {@code event} as one JSON line to today's archive file, creating the target directory
     * first if it does not exist. Never throws — it runs on the EDT (driven from the model's
     * eviction path) so a disk hiccup OR a serialization error must never crash the UI thread.
     * Catches {@link RuntimeException} as well as {@link IOException} (adversarial review L3): a
     * {@code TerminalJson.write} failure is unchecked and would otherwise propagate on the EDT.
     */
    public void archive(CommLogEvent event) {
        Path file = targetDir.resolve("commlog-archive-" + LocalDate.now() + ".jsonl");
        try {
            Files.createDirectories(targetDir);
            Files.writeString(file, TerminalJson.write(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to archive comm-log entry to {}", file, e);
        }
    }
}
