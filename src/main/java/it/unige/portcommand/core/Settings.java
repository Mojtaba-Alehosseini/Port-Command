package it.unige.portcommand.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single canonical game-settings record (task 15; {@code planning/15_negotiation_engine.md}
 * §15.1). Lives in {@code core} rather than {@code negotiation} because it is consumed
 * cross-cutting (tasks 15/20/21/22/24). Loaded once from {@code data/defaults.json} on the
 * classpath (static config, not live shared state — same rationale as
 * {@link it.unige.portcommand.negotiation.VesselTemplates}, mirrored here). {@code randomSeed} is
 * deliberately NOT a field — it lives on {@code GameState} (task 22) and is captured in the save
 * file.
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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Settings(
        @JsonProperty("roundLimit") int roundLimit,
        @JsonProperty("timeLimitSeconds") int timeLimitSeconds,
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
        @JsonProperty("bankruptcyFloorEur") double bankruptcyFloorEur) {

    private static final Set<String> VALID_DIFFICULTIES = Set.of("EASY", "NORMAL", "HARD");
    private static final Settings CACHED = readFromClasspath();

    public Settings {
        if (roundLimit < 2 || roundLimit > 8) {
            throw new IllegalArgumentException("roundLimit out of range 2..8: " + roundLimit);
        }
        if (timeLimitSeconds < 30 || timeLimitSeconds > 180) {
            throw new IllegalArgumentException("timeLimitSeconds out of range 30..180: " + timeLimitSeconds);
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
    }

    /** The hard-coded fallback values (task-15 brief, §15.1) — also what ships in defaults.json. */
    public static Settings defaults() {
        return new Settings(4, 60, false, "NORMAL", 300L, 30, true,
                "microsoft/Phi-4-mini-instruct", 500.0, 5, 30, 5_000.0, -20_000.0);
    }

    /** The classpath-loaded settings, read once and cached (mirrors {@code VesselTemplates}). */
    public static Settings load() {
        return CACHED;
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
