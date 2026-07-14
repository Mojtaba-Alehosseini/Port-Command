package it.unige.portcommand.ontology;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The closed result of a negotiation. Declares the <strong>single canonical</strong>
 * {@link Outcome} taxonomy: tasks 07 ({@code WithdrawalBehaviour}/
 * {@code DealClosedBehaviour}) and 09 ({@code MarketHistoryArtifact.DealRecord})
 * reuse this enum and MUST NOT define their own outcome strings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Deal(
        @JsonProperty("deal_id") String dealId,
        @JsonProperty("vessel_id") String vesselId,
        @JsonProperty("berth_id") String berthId,
        @JsonProperty("final_price") double finalPrice,
        @JsonProperty("final_hours") int finalHours,
        @JsonProperty("closed_at_sim_millis") long closedAtSimMillis,
        @JsonProperty("outcome") Outcome outcome) {

    /** The four — and only four — negotiation outcomes. */
    public enum Outcome {
        /** Both sides agreed; berth assigned. */
        DEAL,
        /** Vessel withdrew because the price never met its threshold (incl. rounds exhausted). */
        WITHDRAW_PRICE,
        /** Negotiation timed out with no agreement. */
        TIMEOUT,
        /** The player (HarbourMaster) explicitly refused. */
        PLAYER_REFUSED
    }

    public Deal {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
    }
}
