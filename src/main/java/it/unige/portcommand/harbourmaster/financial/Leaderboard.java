package it.unige.portcommand.harbourmaster.financial;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The top-5 finished runs, persisted to {@code save/scores.json} (task 20 owns this class;
 * task 22 adds only a serialization round-trip test — CANONICAL_FINDINGS fix 5).
 *
 * <p>Deliberately a SEPARATE file from {@code save/savegame.json} (planning/22 "Hard constraints"):
 * the leaderboard survives across runs and saves, so folding it into a save slot would lose it on
 * a fresh game.
 *
 * <p>Ranked by {@code finalWallet} descending, ties broken by the earlier {@code finalDay} (a
 * player who reached the same money in fewer days played better) and then by {@code recordedOn}.
 * That is <em>near</em>-total, not total: three runs identical in wallet, day AND date still
 * compare equal. Round-trip stability holds anyway because {@code List.sort} is guaranteed stable,
 * so equal entries keep their insertion order through a save/load — but do not weaken the
 * tie-breakers on the assumption that the comparator alone carries it, or two equal scores will
 * swap places between runs and make task 22's round-trip test flake.
 *
 * <h2>Threading / IO</h2>
 * Mutation is {@code synchronized}: {@link #recordIfHighScore} read-modify-writes both the
 * in-memory list and the file. A write failure is logged, never thrown — losing a leaderboard row
 * must not take down a game that is otherwise fine (the run itself is already over). A corrupt or
 * absent file loads as an empty board, same rationale.
 */
public final class Leaderboard {

    private static final Logger log = LoggerFactory.getLogger(Leaderboard.class);

    /** planning/20 "Hard constraints": top 5, in {@code save/scores.json}. */
    public static final int MAX_ENTRIES = 5;
    private static final Path DEFAULT_PATH = Path.of("save", "scores.json");

    private static final Comparator<ScoreRecord> RANKING = Comparator
            .comparingDouble(ScoreRecord::finalWallet).reversed()
            .thenComparingInt(ScoreRecord::finalDay)
            .thenComparing(ScoreRecord::recordedOn);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private final List<ScoreRecord> entries;

    /** Production: {@code save/scores.json} relative to the working directory. */
    public Leaderboard() {
        this(DEFAULT_PATH);
    }

    /** Test seam: an explicit path (a {@code @TempDir}), so tests never touch the real board. */
    public Leaderboard(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.entries = load();
    }

    /**
     * Offers a finished run to the board. Appends, re-ranks, truncates to {@link #MAX_ENTRIES},
     * and persists — so a run that does not make the cut is simply dropped.
     *
     * @return {@code true} if the run made the top 5
     */
    public synchronized boolean recordIfHighScore(double finalWallet, int finalDay, double finalReputation,
                                                   LocalDate date) {
        ScoreRecord candidate = new ScoreRecord(finalWallet, finalDay, finalReputation,
                Objects.requireNonNull(date, "date").toString());
        List<ScoreRecord> merged = new ArrayList<>(entries);
        merged.add(candidate);
        merged.sort(RANKING);
        // Identity, not indexOf: ScoreRecord is a record, so indexOf uses VALUE equality and would
        // find the first EQUAL entry rather than this candidate — reporting "you made the board"
        // for a run that was actually truncated away (e.g. a 6th identical score, or one tying the
        // worst entry exactly). Found by the task-20 adversarial review, which reproduced both.
        boolean made = indexOfIdentity(merged, candidate) < MAX_ENTRIES;
        if (merged.size() > MAX_ENTRIES) {
            merged.subList(MAX_ENTRIES, merged.size()).clear();
        }
        entries.clear();
        entries.addAll(merged);
        persist();
        return made;
    }

    /** The board, best first. Snapshot. */
    public synchronized List<ScoreRecord> top5() {
        return List.copyOf(entries);
    }

    /** Position of the exact object {@code target}, by reference — never by {@code equals}. */
    private static int indexOfIdentity(List<ScoreRecord> list, ScoreRecord target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private List<ScoreRecord> load() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<ScoreRecord> read = mapper.readValue(Files.readAllBytes(file),
                    new TypeReference<List<ScoreRecord>>() { });
            List<ScoreRecord> sorted = new ArrayList<>(read);
            sorted.sort(RANKING);
            if (sorted.size() > MAX_ENTRIES) {
                sorted.subList(MAX_ENTRIES, sorted.size()).clear();
            }
            return sorted;
        } catch (IOException | RuntimeException e) {
            log.warn("could not read leaderboard {} ({}) — starting from an empty board",
                    file, e.toString());
            return new ArrayList<>();
        }
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
        } catch (IOException e) {
            log.warn("could not write leaderboard {} — the run's score is lost, game continues", file, e);
        }
    }
}
