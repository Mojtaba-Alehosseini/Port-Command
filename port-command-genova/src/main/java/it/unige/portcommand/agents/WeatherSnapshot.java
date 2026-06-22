package it.unige.portcommand.agents;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable weather reading. {@code visibility} is a category atom
 * {@code good|fair|poor} (matching the Prolog {@code operation_safe} String
 * contract, not a nautical-mile double); {@code state} is the Markov state
 * {@code sunny|cloudy|stormy}. Broadcast on the wire as
 * {@code {wind_knots, visibility, swell, state, sim_time}}.
 *
 * @param wind       wind speed in knots
 * @param visibility {@code good} / {@code fair} / {@code poor}
 * @param swell      swell height (metres)
 * @param state      {@code sunny} / {@code cloudy} / {@code stormy}
 * @param simTime    sim-millis this reading was taken
 */
public record WeatherSnapshot(
        @JsonProperty("wind") int wind,
        @JsonProperty("visibility") String visibility,
        @JsonProperty("swell") double swell,
        @JsonProperty("state") String state,
        @JsonProperty("sim_time") long simTime) {
}
