package it.unige.portcommand.agents;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON payload of a berth-assignment REQUEST (HarbourMaster → Terminal). The
 * terminal needs the vessel's physical fields to run {@code isCompatible}, so the
 * HM must build all nine fields from the contract + the vessel's spec (task 11 is
 * the producer; this record is the canonical wire contract). Plain DTO — the
 * {@code HandleBerthRequestBehaviour} validates required fields and replies
 * REFUSE("malformed") on anything missing/out-of-range or unparseable.
 */
public record BerthRequest(
        @JsonProperty("vessel_id") String vesselId,
        @JsonProperty("vessel_type") String vesselType,
        @JsonProperty("draft") double draft,
        @JsonProperty("length") double length,
        @JsonProperty("tonnage") int tonnage,
        @JsonProperty("berth_id") String berthId,
        @JsonProperty("eta_sim") long etaSim,
        @JsonProperty("duration_hours") int durationHours,
        @JsonProperty("cargo_class") String cargoClass) {
}
