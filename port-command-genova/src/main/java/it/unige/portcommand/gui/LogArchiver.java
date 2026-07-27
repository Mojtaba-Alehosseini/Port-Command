package it.unige.portcommand.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

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
        // Collections.singletonList, NOT List.of: List.of rejects null eagerly, which would throw
        // OUTSIDE archiveAll's try and break this method's "never throws" contract for a null event.
        archiveAll(Collections.singletonList(event));
    }

    /**
     * Appends every event as one JSON line, in order, in a SINGLE open/write/close. Never throws,
     * for the same reason {@link #archive} does not.
     *
     * <p><b>Audit C-06 (2026-07-27).</b> The per-entry form was designed for the EVICTION path —
     * one write per event once the 500-entry cap is reached, which is fine. {@code CommLogModel.clear()}
     * reused it in a tight loop, so every Load and every New Game did up to 500 sequential
     * {@code createDirectories} + open + append + close calls <b>on the Event Dispatch Thread</b>.
     * On Windows with Defender in the path that is roughly 0.5–3 ms each: a visible hitch at best,
     * and the demo's own "New Game → Storm Event" scenario switch (DEMO_SCRIPT Beat 5) is exactly
     * when it fires. Batching pays one syscall round-trip instead of N; the caller still archives
     * BEFORE it discards, exactly as the eviction path does.
     *
     * <p>A single un-serialisable event is skipped and logged, never fatal to the rest of the batch —
     * the per-entry form degraded that way and the batched form must too.
     */
    public void archiveAll(List<CommLogEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        Path file = targetDir.resolve("commlog-archive-" + LocalDate.now() + ".jsonl");
        // Serialize per event, each in its own try: batching must not turn "one un-serialisable
        // entry is dropped" into "the whole 500-entry batch is lost". The per-entry loop this
        // replaced degraded that way, and a batch that shares one try would not — found by the
        // adversarial review of the C-06 fix, 2026-07-27.
        StringBuilder lines = new StringBuilder();
        int skipped = 0;
        for (CommLogEvent event : events) {
            try {
                lines.append(TerminalJson.write(event)).append(System.lineSeparator());
            } catch (RuntimeException e) {
                skipped++;
                log.warn("Skipping an un-serialisable comm-log entry while archiving", e);
            }
        }
        if (skipped > 0) {
            log.warn("{} of {} comm-log entries could not be serialised and were not archived",
                    skipped, events.size());
        }
        if (lines.length() == 0) {
            return;
        }
        try {
            Files.createDirectories(targetDir);
            Files.writeString(file, lines.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to archive {} comm-log entr(ies) to {}", events.size(), file, e);
        }
    }
}
