package it.unige.portcommand.ontology;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A pre-agreed contracted-vessel service: a fixed fee and duration for a vessel
 * arriving at a known berth. Drives the automatic contracted-vessel pipeline.
 */
public record ServiceContract(
        @JsonProperty("contract_id") String contractId,
        @JsonProperty("vessel_id") String vesselId,
        @JsonProperty("berth_id") String berthId,
        @JsonProperty("contracted_fee") double contractedFee,
        @JsonProperty("contracted_hours") int contractedHours,
        @JsonProperty("expected_arrival_sim_millis") long expectedArrivalSimMillis) {

    public ServiceContract {
        if (contractedFee <= 0) {
            throw new IllegalArgumentException("contractedFee must be > 0, got " + contractedFee);
        }
        if (contractedHours < 1 || contractedHours > 24) {
            throw new IllegalArgumentException("contractedHours must be in 1..24, got " + contractedHours);
        }
    }
}
