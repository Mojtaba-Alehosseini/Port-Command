package it.unige.portcommand.harbourmaster.financial;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One finished run on the leaderboard (task 20; task 22 adds only the Jackson round-trip test —
 * CANONICAL_FINDINGS fix 5 / MASTER_PLAN §9 fix 5: this file is owned HERE, not there).
 *
 * <p>Snake_case wire keys and explicit {@link JsonProperty} annotations, matching the established
 * convention of the other serialised records ({@code Deal}, {@code Offer}, {@code ServiceContract}).
 *
 * @param finalWallet     the run's ending wallet (€) — the ranking key
 * @param finalDay        the game day the run ended on
 * @param finalReputation the run's ending reputation, 0..100
 * @param recordedOn      ISO-8601 date the run was recorded ({@code LocalDate.toString()}), kept as
 *                        a String so the record stays a plain Jackson DTO with no JavaTimeModule
 *                        dependency — no other serialised record in this codebase registers one
 */
public record ScoreRecord(
        @JsonProperty("final_wallet") double finalWallet,
        @JsonProperty("final_day") int finalDay,
        @JsonProperty("final_reputation") double finalReputation,
        @JsonProperty("recorded_on") String recordedOn) {
}
