package it.unige.portcommand.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single canonical game-settings record (task 15; {@code planning/15_negotiation_engine.md}
 * §15.1). Lives in {@code core} rather than {@code negotiation} because it is consumed
 * cross-cutting (tasks 15/20/21/22/24). {@code randomSeed} is deliberately NOT a field — it
 * lives on {@code GameState} (task 22) and is captured in the save file.
 *
 * <p><b>S9 (2026-08-17): {@code load()} returns the session's EFFECTIVE settings, no longer a
 * boot-frozen classpath constant.</b> The value is an immutable record swapped atomically
 * (volatile) at exactly three kinds of moment, all world boundaries or explicit player action:
 * {@link it.unige.portcommand.persistence.GameSession} applies the SAVED block on load and the
 * sandbox/scenario baseline on new game (both before agents boot — ADR-15's in-place-restore
 * slot), and {@code MainWindow.applySettings} writes the form's changes through. Every existing
 * {@code Settings.load()} read site — engine constructions at spawn, Round-N-of-M, the countdown,
 * the recommendation budgets — therefore sees restored values after a load with zero call-site
 * changes, and {@code GameStateBuilder}'s snapshot (which always wrote {@code load()}) now
 * captures the LIVE values instead of silently recording defaults. This closes the fix-campaign
 * ledger's "live Settings revert on load" limitation.
 *
 * <p>{@code defaults.json} also carries two unrelated blocks that their owners read independently
 * — {@code llm.timeout_ms} ({@link it.unige.portcommand.nlp.LLMBridge}) and {@code reputation}
 * ({@code harbourmaster.financial.ReputationRules}, a per-outcome delta TABLE rather than the flat
 * scalars this record holds). {@link JsonIgnoreProperties} lets this record bind against that same
 * shared file without choking on the extra objects.
 *
 * <p>{@code dailyTargetEur} was added by task 20 (the canonical €5,000 daily target,
 * {@code planning/20} "Notes"); it lives here rather than in a financial-only config because the
 * HUD and the EOD summary both read it, and this record is the designed cross-task home.
 *
 * <p>{@code bankruptcyFloorEur} was added by task 24 (session brief, 2026-07-17; ADR-14): the
 * game-over rule is "wallet below this floor at end-of-day for 3 CONSECUTIVE days", −€20,000
 * canonical — NOT planning/24's older "any negative wallet at EOD", which would bankrupt a
 * zero-deal day-1 run on the €5,200 fixed cost alone. {@code GameOverGuard} is the sole consumer.
 *
 * <p><b>S14 (2026-10-11, playability/timing — ADR-18).</b> {@code timeLimitSeconds} is RETIRED: it
 * was the nominal 60 s window behind the old "~Ns remaining" countdown and the Assistant's urgency
 * time term, and both now read the vessel's real conversation clock instead. Old saves still carry
 * the key; {@link JsonIgnoreProperties} drops it. {@code gameSpeed} is NEW: the player's ×1/×2/×4
 * speed control, saved with the rest of this block (save schemaVersion stays 1) — a save written
 * before it existed binds the field as 0, which reads as ×1. The shipped defaults moved with the
 * same decision: a 60-minute game day ({@code realSecondsPerGameDay} 300 → 3600) and 6 negotiation
 * rounds ({@code roundLimit} 4 → 6).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Settings(
        @JsonProperty("roundLimit") int roundLimit,
        @JsonProperty("autopilotEnabled") boolean autopilotEnabled,
        @JsonProperty("difficulty") String difficulty,
        @JsonProperty("realSecondsPerGameDay") long realSecondsPerGameDay,
        @JsonProperty("maxGameDays") int maxGameDays,
        @JsonProperty("firstLaunch") boolean firstLaunch,
        @JsonProperty("llmModel") String llmModel,
        @JsonProperty("withdrawalPenaltyEur") double withdrawalPenaltyEur,
        @JsonProperty("cnpReplyBySimSeconds") int cnpReplyBySimSeconds,
        @JsonProperty("cnpRetryAfterSimSeconds") int cnpRetryAfterSimSeconds,
        @JsonProperty("dailyTargetEur") double dailyTargetEur,
        @JsonProperty("bankruptcyFloorEur") double bankruptcyFloorEur,
        @JsonProperty("gameSpeed") int gameSpeed) {

    /** S14: the speed multipliers the player can choose, slowest first. */
    public static final List<Integer> GAME_SPEEDS = List.of(1, 2, 4);

    private static final Set<String> VALID_DIFFICULTIES = Set.of("EASY", "NORMAL", "HARD");
    /** The session's effective settings (S9): classpath-seeded, swapped whole at world
     * boundaries and by the form's write-through. Immutable record + volatile = atomic reads. */
    private static volatile Settings current = readFromClasspath();

    public Settings {
        if (roundLimit < 2 || roundLimit > 8) {
            throw new IllegalArgumentException("roundLimit out of range 2..8: " + roundLimit);
        }
        if (!VALID_DIFFICULTIES.contains(difficulty)) {
            throw new IllegalArgumentException("difficulty must be EASY/NORMAL/HARD: " + difficulty);
        }
        if (dailyTargetEur <= 0) {
            throw new IllegalArgumentException("dailyTargetEur must be > 0: " + dailyTargetEur);
        }
        if (bankruptcyFloorEur >= 0) {
            throw new IllegalArgumentException("bankruptcyFloorEur must be < 0: " + bankruptcyFloorEur);
        }
        if (gameSpeed == 0) {
            gameSpeed = 1; // a save from before S14 has no such key and binds 0 — it was running at ×1
        }
        if (!GAME_SPEEDS.contains(gameSpeed)) {
            throw new IllegalArgumentException("gameSpeed must be one of " + GAME_SPEEDS + ": " + gameSpeed);
        }
    }

    /** The hard-coded fallback values (task-15 brief, §15.1) — also what ships in defaults.json. */
    public static Settings defaults() {
        return new Settings(6, false, "NORMAL", 3_600L, 30, true,
                "microsoft/Phi-4-mini-instruct", 500.0, 5, 30, 5_000.0, -20_000.0, 1);
    }

    /** The session's EFFECTIVE settings (see the class javadoc's S9 note). */
    public static Settings load() {
        return current;
    }

    /**
     * S9: swap the effective settings (null = no-op, so a settings-less pre-S9 save applies
     * nothing and boot defaults stand). Callers are the three sanctioned sites in the class
     * javadoc; the record's compact constructor re-validates every swap.
     */
    public static void applyLive(Settings settings) {
        if (settings != null) {
            current = settings;
        }
    }

    /** S9: the pristine classpath baseline — what a fresh sandbox game applies at boot. */
    public static Settings classpathDefaults() {
        return readFromClasspath();
    }

    // S9 with-ers for the live-changeable knobs (records are immutable — new instance each).
    public Settings withRoundLimit(int v) {
        return new Settings(v, autopilotEnabled, difficulty, realSecondsPerGameDay,
                maxGameDays, firstLaunch, llmModel, withdrawalPenaltyEur, cnpReplyBySimSeconds,
                cnpRetryAfterSimSeconds, dailyTargetEur, bankruptcyFloorEur, gameSpeed);
    }

    public Settings withAutopilotEnabled(boolean v) {
        return new Settings(roundLimit, v, difficulty, realSecondsPerGameDay,
                maxGameDays, firstLaunch, llmModel, withdrawalPenaltyEur, cnpReplyBySimSeconds,
                cnpRetryAfterSimSeconds, dailyTargetEur, bankruptcyFloorEur, gameSpeed);
    }

    public Settings withDifficulty(String v) {
        return new Settings(roundLimit, autopilotEnabled, v, realSecondsPerGameDay,
                maxGameDays, firstLaunch, llmModel, withdrawalPenaltyEur, cnpReplyBySimSeconds,
                cnpRetryAfterSimSeconds, dailyTargetEur, bankruptcyFloorEur, gameSpeed);
    }

    public Settings withRealSecondsPerGameDay(long v) {
        return new Settings(roundLimit, autopilotEnabled, difficulty, v,
                maxGameDays, firstLaunch, llmModel, withdrawalPenaltyEur, cnpReplyBySimSeconds,
                cnpRetryAfterSimSeconds, dailyTargetEur, bankruptcyFloorEur, gameSpeed);
    }

    public Settings withLlmModel(String v) {
        return new Settings(roundLimit, autopilotEnabled, difficulty,
                realSecondsPerGameDay, maxGameDays, firstLaunch, v, withdrawalPenaltyEur,
                cnpReplyBySimSeconds, cnpRetryAfterSimSeconds, dailyTargetEur, bankruptcyFloorEur, gameSpeed);
    }

    /** S14: the speed control's write-through. */
    public Settings withGameSpeed(int v) {
        return new Settings(roundLimit, autopilotEnabled, difficulty,
                realSecondsPerGameDay, maxGameDays, firstLaunch, llmModel, withdrawalPenaltyEur,
                cnpReplyBySimSeconds, cnpRetryAfterSimSeconds, dailyTargetEur, bankruptcyFloorEur, v);
    }

    private static Settings readFromClasspath() {
        try (InputStream in = Settings.class.getResourceAsStream("/data/defaults.json")) {
            if (in == null) {
                throw new IllegalStateException("defaults.json not found on classpath (/data/)");
            }
            return new ObjectMapper().readValue(in, Settings.class);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load defaults.json", e);
        }
    }
}
