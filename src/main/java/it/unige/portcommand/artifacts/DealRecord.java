package it.unige.portcommand.artifacts;

import com.fasterxml.jackson.annotation.JsonProperty;
import it.unige.portcommand.ontology.Deal;

/**
 * One closed-or-withdrawn negotiation outcome, appended to {@link MarketHistoryArtifact}
 * by the walk-in vessel's deal behaviours (task 07) and persisted by task 22. Reuses
 * the canonical {@link Deal.Outcome} enum (task 02) — no ad-hoc outcome strings.
 * snake_case wire keys so the save file (task 22) serializes it directly.
 *
 * @param vesselType    vessel type of the negotiating vessel
 * @param durationHours service duration negotiated
 * @param price         agreed price (DEAL) or last offer (withdrawal)
 * @param simTime       sim-millis the outcome was recorded
 * @param outcome       DEAL / WITHDRAW_PRICE / TIMEOUT / PLAYER_REFUSED
 */
public record DealRecord(
        @JsonProperty("vessel_type") String vesselType,
        @JsonProperty("duration_hours") int durationHours,
        @JsonProperty("price") double price,
        @JsonProperty("sim_time") long simTime,
        @JsonProperty("outcome") Deal.Outcome outcome) {
}
