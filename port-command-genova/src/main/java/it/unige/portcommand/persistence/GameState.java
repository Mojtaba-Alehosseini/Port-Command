package it.unige.portcommand.persistence;

import java.util.List;

import it.unige.portcommand.artifacts.DealRecord;
import it.unige.portcommand.core.Settings;
import it.unige.portcommand.persistence.dto.CustomsStateDTO;
import it.unige.portcommand.persistence.dto.HarbourMasterStateDTO;
import it.unige.portcommand.persistence.dto.TerminalStateDTO;
import it.unige.portcommand.persistence.dto.TugStateDTO;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import it.unige.portcommand.persistence.dto.WeatherStateDTO;

/**
 * The whole save file (task 22, §3.14): one flat Jackson record tree, {@code schemaVersion}
 * an integer at the JSON root (the loader's hard gate). Keys are the record component
 * names (camelCase, matching planning/22 §22.3's sketch); nested task-02/06 records
 * ({@link DealRecord}, {@code BerthOccupancy}, {@code WeatherSnapshot}, {@code Position})
 * keep their own snake_case annotations.
 *
 * <p><b>What is deliberately NOT here</b> (all dated 2026-07-17, planning/22):
 * the leaderboard ({@code save/scores.json} is a separate file that must survive across
 * runs — the hard-constraints section outranks the §22.3 sketch's {@code leaderboardTop5}
 * field); the Prolog KB (static rules R1–R30, no game state — runtime facts are re-derived
 * from the restored agents); open negotiations (manual save refuses, autosave drops them);
 * and policy predicates ({@code policyTriggers} carries the player's original text, re-parsed
 * through the pure {@code nlp.PolicyParser} regex DSL at load).
 *
 * <p><b>Determinism invariant:</b> every serialized map is a LinkedHashMap in a defined
 * order and every list of keyed things is sorted by its key at snapshot, so identical
 * state serialises to identical bytes (the round-trip byte-diff test).
 *
 * @param schemaVersion always {@value SaveLoadManager#SCHEMA_VERSION}; any other value is
 *                      a hard {@link SchemaVersionException}
 * @param savedAt       ISO-8601 UTC wall-clock instant — an audit field like the
 *                      leaderboard's {@code recorded_on}, excluded from byte-diff testing
 * @param randomSeed    {@code RandomSource.saveSeedAt(simMillis)} — reseeding the master
 *                      RNG with this on load makes repeated loads stream-identical
 * @param settings      the full canonical {@link Settings} record
 * @param simClock      sim instant + mapping rate (day/time-of-day are functions of it)
 * @param wallet        wallet balance (€)
 * @param reputation    reputation, INT 0..100 (project-wide canonical type)
 * @param consecutiveDeepDebtDays {@code GameOverGuard}'s live bankruptcy streak
 * @param lastDeepDebtDay         the streak's last deep-debt day number
 * @param marketHistory last ≤200 deal records, insertion order
 * @param policyTriggers the player's registered autopilot policy texts, registration order
 * @param activeScenario the running scenario's key, or null in sandbox play (task 23) —
 *                      the loader reinstalls the scenario's remaining script from it
 * @param firedEventIds already-fired scripted-event ids, sorted (task 23's replay guard —
 *                      scenario events AND the daily contracted stream's day-qualified
 *                      ids). <b>Always serialized</b>: an empty {@code []} when nothing
 *                      has fired, normalised non-null here so the NON_NULL mapper can
 *                      never omit it. Both fields are additive within schema v1 — a
 *                      pre-task-23 save loads with sandbox semantics; no version bump.
 * @param agents        the per-agent DTO tree
 */
public record GameState(
        int schemaVersion,
        String savedAt,
        long randomSeed,
        Settings settings,
        SimClockState simClock,
        double wallet,
        int reputation,
        int consecutiveDeepDebtDays,
        int lastDeepDebtDay,
        List<DealRecord> marketHistory,
        List<String> policyTriggers,
        String activeScenario,
        List<String> firedEventIds,
        AgentsState agents) {

    public GameState {
        firedEventIds = firedEventIds == null ? List.of() : List.copyOf(firedEventIds);
    }

    /** Sim instant + wall↔sim mapping. Day, hour and minute are derived from these two. */
    public record SimClockState(long simMillis, long realSecondsPerGameDay) {
    }

    /**
     * The per-agent snapshot tree: the fixed fleet plus every persisted active vessel
     * (single flat {@link VesselStateDTO}; the loader branches on {@code channel}).
     * Tugs, terminals and vessels are sorted by id at snapshot (determinism rule).
     */
    public record AgentsState(
            HarbourMasterStateDTO harbourMaster,
            WeatherStateDTO weather,
            CustomsStateDTO customs,
            List<TugStateDTO> tugs,
            List<TerminalStateDTO> terminals,
            List<VesselStateDTO> activeVessels) {
    }
}
