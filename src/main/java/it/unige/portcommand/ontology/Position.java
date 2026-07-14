package it.unige.portcommand.ontology;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable 2-D position with heading, shared by the vessel/tug agents, the
 * port-state artefact (task 06), and the GUI map. Defined once here (task 02
 * precedes task 05) so every consumer imports a single type.
 *
 * <p>{@code headingDeg} is degrees clockwise from north, in {@code [0, 360)} by
 * convention (not enforced — interpolation may transiently overshoot).
 */
public record Position(
        @JsonProperty("x") double x,
        @JsonProperty("y") double y,
        @JsonProperty("heading_deg") double headingDeg) {
}
