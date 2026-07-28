package it.unige.portcommand.persistence;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The persistence I/O facade (task 22): serialise a {@link GameState} to disk atomically,
 * read one back through the schema-version gate, validate its invariants. Pure Jackson —
 * no Java serialisation, no default typing ({@code security-audit}: the mapper binds only
 * the closed {@code GameState} record tree).
 *
 * <p><b>Slots (v1):</b> exactly {@code save/savegame.json} (Game → Save) and
 * {@code save/autosave.json} (end-of-day) — no multi-slot files (planning/22 scope cut).
 * {@code save/scores.json} is the leaderboard's own file, owned by task 20.
 *
 * <p><b>Atomic writes:</b> the JSON goes to a sibling {@code <target>.tmp}, the channel is
 * fsynced, then {@code Files.move(ATOMIC_MOVE + REPLACE_EXISTING)} publishes it — a JVM
 * killed mid-save can leave a stale {@code .tmp} but never a truncated
 * {@code savegame.json}. Falls back to a plain {@code REPLACE_EXISTING} move only if the
 * OS refuses ATOMIC_MOVE (not the case for same-directory moves on NTFS).
 */
public final class SaveLoadManager {

    public static final int SCHEMA_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(SaveLoadManager.class);

    private final ObjectMapper mapper = JsonMapper.builder()
            // Jackson 2.16.x — records bind natively; NON_NULL keeps the flat VesselStateDTO's
            // other-channel fields out of the file (planning/22's Step 22.5 sketch; the old
            // MAPPER_FEATURE.PROPAGATE_TRANSIENT_MARKER note is irrelevant to records).
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    private final Path saveDirectory;

    /** Production: the {@code save/} directory next to the working directory (task-01 layout). */
    public SaveLoadManager() {
        this(Path.of("save"));
    }

    /** Test seam: an explicit directory (a {@code @TempDir}), so tests never touch real saves. */
    public SaveLoadManager(Path saveDirectory) {
        this.saveDirectory = saveDirectory;
    }

    public Path savegamePath() {
        return saveDirectory.resolve("savegame.json");
    }

    public Path autosavePath() {
        return saveDirectory.resolve("autosave.json");
    }

    /** {@code true} until a manual save exists — task 21's first-launch probe. */
    public boolean isFirstLaunch() {
        return !Files.exists(savegamePath());
    }

    /**
     * A save slot's headline metadata for task 21's {@code SaveLoadDialog} preview — day, wallet
     * and the wall-clock save instant. Deliberately NOT a full {@link #load}: the dialog must
     * show every slot's summary on open without rebuilding the world (a real load tears the JADE
     * container down), so this reads only the three scalar fields off the JSON tree.
     */
    public record SaveSlotInfo(int day, double wallet, String savedAt) {
    }

    /**
     * Reads a slot's {@link SaveSlotInfo} without materialising a {@link GameState}. Returns empty
     * for a missing file, unreadable JSON, a non-object root, or a wrong-{@code schemaVersion}
     * file — the preview simply shows "empty" for any slot it cannot summarise, and never throws
     * on the EDT.
     */
    public java.util.Optional<SaveSlotInfo> peek(Path source) {
        if (!Files.exists(source)) {
            return java.util.Optional.empty();
        }
        try (var in = Files.newInputStream(source)) {
            JsonNode root = mapper.readTree(in);
            if (root == null || !root.isObject() || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
                return java.util.Optional.empty();
            }
            long simMillis = root.path("simClock").path("simMillis").asLong(0L);
            double wallet = root.path("wallet").asDouble(0.0);
            String savedAt = root.path("savedAt").isMissingNode() || root.path("savedAt").isNull()
                    ? null : root.path("savedAt").asText();
            return java.util.Optional.of(new SaveSlotInfo(
                    it.unige.portcommand.util.SimClock.gameDayOf(simMillis), wallet, savedAt));
        } catch (IOException e) {
            log.warn("peek of {} failed — slot shown as empty", source, e);
            return java.util.Optional.empty();
        }
    }

    /** The shared, identically-configured mapper — fixtures and agents' Mementoable impls use it
     * so a hand-built test state and a production save round-trip through the SAME modules. */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Serialises {@code state} and atomically publishes it at {@code target}. Returns the
     * target path; logs path, schema version and byte count at INFO (logging-patterns).
     */
    public Path save(Path target, GameState state) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            out.write(json);
        }
        // fsync via a re-opened channel (Files.newOutputStream exposes no FD), so the rename
        // below never publishes unflushed bytes.
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("ATOMIC_MOVE unsupported for {} — falling back to REPLACE_EXISTING", target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("saved {} (schemaVersion {}, {} bytes)", target, state.schemaVersion(), json.length);
        return target;
    }

    /**
     * Reads and validates a save. Missing file, unparseable JSON, a binding failure or an
     * invariant violation → {@link CorruptSaveException}; a parseable file whose
     * {@code schemaVersion} is not {@value #SCHEMA_VERSION} → {@link SchemaVersionException}
     * (checked FIRST, so a future-version file reports its version, not a binding error).
     */
    public GameState load(Path source) throws SchemaVersionException, CorruptSaveException {
        JsonNode root;
        try {
            root = mapper.readTree(Files.newInputStream(source));
        } catch (NoSuchFileException e) {
            throw new CorruptSaveException("Save file not found: " + source, e);
        } catch (IOException e) {
            throw new CorruptSaveException("Save file is not readable JSON: " + source, e);
        }
        if (root == null || !root.isObject()) {
            throw new CorruptSaveException("Save file is not a JSON object: " + source);
        }
        int version = root.path("schemaVersion").asInt(-1);
        if (version != SCHEMA_VERSION) {
            throw new SchemaVersionException(version);
        }
        GameState state;
        try {
            state = mapper.treeToValue(root, GameState.class);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException covers Jackson's wrapping of a record compact
            // constructor's validation throw (e.g. Settings range checks, VesselSpec types).
            throw new CorruptSaveException("Save file failed to bind: " + e.getMessage(), e);
        }
        validate(state, source.toString());
        log.info("loaded {} (schemaVersion {}, day {}, wallet {})",
                source, version, state.simClock() == null ? "?" : gameDayOf(state), state.wallet());
        return state;
    }

    /**
     * The same invariant gate {@link #load} applies, for a state built in memory — task
     * 23's scenario boot runs its {@code GameStateBuilder.fromScenario} output through
     * this before teardown, so a scenario-built state is provably save-shaped and a bad
     * one fails BEFORE the running game is touched (the task-22 validate-first rule).
     */
    public void validateState(GameState state, String label) throws CorruptSaveException {
        validate(state, label);
    }

    private static String gameDayOf(GameState state) {
        return String.valueOf(it.unige.portcommand.util.SimClock.gameDayOf(state.simClock().simMillis()));
    }

    /** Schema invariants beyond type binding — each failure is one clear message. */
    private static void validate(GameState state, String source) throws CorruptSaveException {
        require(state.settings() != null, "settings missing", source);
        require(state.simClock() != null, "simClock missing", source);
        require(state.simClock().simMillis() >= 0, "simClock.simMillis negative", source);
        require(state.simClock().realSecondsPerGameDay() > 0, "simClock.realSecondsPerGameDay not positive", source);
        require(state.reputation() >= 0 && state.reputation() <= 100,
                "reputation out of 0..100: " + state.reputation(), source);
        require(state.agents() != null, "agents missing", source);
        require(state.agents().tugs() != null && state.agents().tugs().size() == 4,
                "expected exactly 4 tugs", source);
        Set<String> tugIds = new java.util.HashSet<>();
        for (var tug : state.agents().tugs()) {
            tugIds.add(tug.tugId());
        }
        require(tugIds.equals(Set.of("tug_1", "tug_2", "tug_3", "tug_4")),
                "tug ids must be exactly tug_1..tug_4, got " + tugIds, source);
        require(state.agents().terminals() != null && state.agents().terminals().size() == 2,
                "expected exactly 2 terminals", source);
        require(state.agents().harbourMaster() != null, "agents.harbourMaster missing", source);
        require(state.agents().weather() != null && state.agents().weather().current() != null,
                "agents.weather.current missing", source);
        require(state.agents().customs() != null, "agents.customs missing", source);
        Set<String> channels = Set.of(VesselStateDTO.CHANNEL_CONTRACTED, VesselStateDTO.CHANNEL_WALK_IN);
        for (VesselStateDTO v : state.agents().activeVessels() == null
                ? java.util.List.<VesselStateDTO>of() : state.agents().activeVessels()) {
            require(v.id() != null && !v.id().isBlank(), "vessel with blank id", source);
            require(VesselSpec.knownVesselTypes().contains(v.vesselType()),
                    "unknown vessel type '" + v.vesselType() + "' for " + v.id()
                            + " (the 5 canonical types only — never 'pleasure')", source);
            require(channels.contains(v.channel()),
                    "unknown channel '" + v.channel() + "' for " + v.id(), source);
            require(v.currentPhase() != null && VesselStateDTO.PHASES.contains(v.currentPhase()),
                    "unknown flow phase '" + v.currentPhase() + "' for " + v.id(), source);
            require(stageExists(v.stage()), "unknown tracking stage '" + v.stage() + "' for " + v.id(), source);
        }
    }

    private static boolean stageExists(String stage) {
        if (stage == null) {
            return false;
        }
        try {
            VesselTracking.Stage.valueOf(stage);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void require(boolean condition, String message, String source) throws CorruptSaveException {
        if (!condition) {
            throw new CorruptSaveException("Invalid save " + source + ": " + message);
        }
    }
}
