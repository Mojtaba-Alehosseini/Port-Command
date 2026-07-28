package it.unige.portcommand.ontology;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A pre-agreed contracted-vessel service: a fixed fee and duration for a vessel
 * arriving at a known berth. Drives the automatic contracted-vessel pipeline.
 *
 * <p><b>Task 23 (2026-07-18):</b> gained {@code vesselType} — the live contracted
 * spawner ({@code scenario.ContractSchedule}) must construct a {@link VesselSpec}
 * from the contract alone, and the old entries carried no type. Validated against
 * the 5 canonical types (CLAUDE.md rule 9). {@code expectedArrivalSimMillis} is,
 * since the same change, a <b>time-of-day offset</b> (sim-millis past midnight the
 * vessel is due, e.g. 09:00 = 32_400_000) rather than an absolute instant: the
 * schedule recurs DAILY, so an absolute stamp would be meaningful for one day only.
 * The pre-task-23 {@code 0} placeholders were explicitly "superseded by task 23's
 * scenario loader" ({@code AgentRoster.loadContracts} javadoc).
 */
public record ServiceContract(
        @JsonProperty("contract_id") String contractId,
        @JsonProperty("vessel_id") String vesselId,
        @JsonProperty("vessel_type") String vesselType,
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
        if (!VesselSpec.knownVesselTypes().contains(vesselType)) {
            throw new IllegalArgumentException("unknown vessel type: " + vesselType);
        }
    }
}
