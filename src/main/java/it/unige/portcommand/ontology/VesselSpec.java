package it.unige.portcommand.ontology;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Static description of a vessel requesting port service. Plain DTO — no
 * polymorphic (de)serialisation yet (that arrives with the vessel agents in
 * task 07).
 *
 * <p>{@code vesselType} is validated against the five canonical ship types
 * (CLAUDE.md rule 9 — locked). That set mirrors the ontology's
 * {@code vessel_type/1} facts; {@code OntologyValidationTest} guards against drift
 * between this hardcoded set and the generated {@code port_ontology.pl}.
 */
public record VesselSpec(
        @JsonProperty("vessel_id") String vesselId,
        @JsonProperty("vessel_type") String vesselType,
        @JsonProperty("draft") double draft,
        @JsonProperty("length") double length,
        @JsonProperty("tonnage") int tonnage,
        @JsonProperty("cargo_class") String cargoClass,
        @JsonProperty("eta_arrival_sim_millis") long etaArrivalSimMillis) {

    private static final Set<String> KNOWN_VESSEL_TYPES =
            Set.of("tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship");

    public VesselSpec {
        if (draft <= 0) {
            throw new IllegalArgumentException("draft must be > 0, got " + draft);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be > 0, got " + length);
        }
        if (tonnage <= 0) {
            throw new IllegalArgumentException("tonnage must be > 0, got " + tonnage);
        }
        if (!KNOWN_VESSEL_TYPES.contains(vesselType)) {
            throw new IllegalArgumentException("unknown vessel type: " + vesselType);
        }
    }

    /** The five canonical vessel types (CLAUDE.md rule 9); mirrors {@code vessel_type/1}. */
    public static Set<String> knownVesselTypes() {
        return KNOWN_VESSEL_TYPES;
    }
}
