package it.unige.portcommand.agents;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import it.unige.portcommand.ontology.Position;

import java.util.List;

/**
 * Tagged hierarchy of agent init-args records, passed as {@code args[0]} to a
 * spawned agent. {@code VesselSpec}/{@code Position} (task 02) are reused as-is
 * for vessel agents and are not part of this sealed set.
 *
 * <p>{@code TugInitArgs} is defined ONCE here (task 08 reuses it, does not
 * redeclare): {@code initialFuel} seeds the agent's live, mutable {@code fuelState}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = InitArgs.TugInitArgs.class, name = "tug"),
        @JsonSubTypes.Type(value = InitArgs.TerminalInitArgs.class, name = "terminal"),
        @JsonSubTypes.Type(value = InitArgs.WeatherInitArgs.class, name = "weather")
})
public sealed interface InitArgs
        permits InitArgs.TugInitArgs, InitArgs.TerminalInitArgs, InitArgs.WeatherInitArgs {

    /** Tug fleet member init. {@code initialFuel} is a 0..1 fraction seeding {@code fuelState}. */
    record TugInitArgs(String tugId, Position basePosition, double baseFare,
                       double fuelCostPerKm, double topSpeedKnots, double initialFuel)
            implements InitArgs {
    }

    /** Terminal init: which berths it manages and its total crane capacity. */
    record TerminalInitArgs(String terminalId, List<String> managedBerths, int cranesTotal)
            implements InitArgs {
    }

    /**
     * Weather init: the starting snapshot, the Markov transition matrix, and an
     * optional list of scripted overrides.
     *
     * <p>The overrides are a legacy, test-only route (comment corrected in S16, 2026-10-13: it
     * used to say the Storm scenario scripts a sim-minute-8 event through here). The shipped
     * game always passes an empty list, and the storm scenario scripts its weather through the
     * scenario engine's {@code WeatherChange} event, not through these args; only
     * {@code WeatherAgentIT} supplies any. Since S16 an override replaces the first hourly
     * weather step at or after its sim-minute ({@code WeatherStepper}).
     */
    record WeatherInitArgs(WeatherSnapshot initial, TransitionMatrix matrix,
                           List<ScriptedWeather> overrides) implements InitArgs {

        /** A scenario-scripted snapshot applied at {@code simMinute}, overriding the Markov draw. */
        public record ScriptedWeather(int simMinute, WeatherSnapshot snapshot) {
        }
    }
}
