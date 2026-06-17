package it.unige.portcommand.ontology;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single negotiation move over price (&euro;/hour) and duration (hours),
 * exchanged between a walk-in vessel and the HarbourMaster. One offer per round
 * (up to four rounds).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Offer(
        @JsonProperty("price") double price,
        @JsonProperty("duration_hours") int durationHours,
        @JsonProperty("berth_id") String berthId,
        @JsonProperty("from_agent") String fromAgent,
        @JsonProperty("to_agent") String toAgent,
        @JsonProperty("timestamp") long timestamp) {

    public Offer {
        if (price <= 0) {
            throw new IllegalArgumentException("price must be > 0, got " + price);
        }
        if (durationHours < 1 || durationHours > 24) {
            throw new IllegalArgumentException("durationHours must be in 1..24, got " + durationHours);
        }
    }
}
