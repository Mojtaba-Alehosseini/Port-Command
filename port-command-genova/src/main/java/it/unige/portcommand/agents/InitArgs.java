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
     * optional list of scripted overrides (empty by default; the Storm scenario
     * scripts a sim-minute-8 threshold event — task 09/23).
     */
    record WeatherInitArgs(WeatherSnapshot initial, TransitionMatrix matrix,
                           List<ScriptedWeather> overrides) implements InitArgs {

        /** A scenario-scripted snapshot applied at {@code simMinute}, overriding the Markov draw. */
        public record ScriptedWeather(int simMinute, WeatherSnapshot snapshot) {
        }
    }
}
