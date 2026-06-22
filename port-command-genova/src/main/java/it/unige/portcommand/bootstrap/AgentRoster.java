package it.unige.portcommand.bootstrap;

import java.util.List;

import it.unige.portcommand.agents.AssistantAgent;
import it.unige.portcommand.agents.CustomsAgent;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.agents.InitArgs.TerminalInitArgs;
import it.unige.portcommand.agents.InitArgs.TugInitArgs;
import it.unige.portcommand.agents.InitArgs.WeatherInitArgs;
import it.unige.portcommand.agents.TerminalAgent;
import it.unige.portcommand.agents.TransitionMatrix;
import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.WeatherAgent;
import it.unige.portcommand.agents.WeatherSnapshot;
import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single place that constructs the fixed agent fleet (10 instances). Live
 * shared services (port state, sim clock, master RNG, market history) are
 * constructor-injected through the {@code Object[]} args channel — never statics.
 * Weather gets {@code [WeatherInitArgs, RandomSource]}; Customs {@code [RandomSource]};
 * Assistant {@code [MarketHistoryArtifact]}; terminals {@code [TerminalInitArgs,
 * PortStateArtifact, SimClock]}. Vessel agents are spawned dynamically elsewhere.
 *
 * <p>(The growing parameter list is a known smell — a {@code GameServices} bundle is
 * the planned cleanup once tasks 10/11/15 add their injected deps.)
 */
public final class AgentRoster {

    private static final Logger log = LoggerFactory.getLogger(AgentRoster.class);
    private static final Position TUG_BASE = new Position(0.0, 0.0, 0.0);

    private AgentRoster() {
    }

    public static void spawnSingletonsAndFleet(JadeAgentSpawner spawner, PortStateArtifact portState,
                                               SimClock simClock, RandomSource randomSource,
                                               MarketHistoryArtifact marketHistory) {
        spawner.spawn("harbour_master", HarbourMasterAgent.class, null);
        spawner.spawn("weather_agent", WeatherAgent.class,
                new Object[] {defaultWeatherInit(), randomSource, simClock});
        spawner.spawn("customs_agent", CustomsAgent.class, new Object[] {randomSource});
        spawner.spawn("assistant_agent", AssistantAgent.class, new Object[] {marketHistory});

        spawner.spawn("terminal_container", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_container", List.of("berth_2"), 4), portState, simClock});
        spawner.spawn("terminal_general", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_general", List.of("berth_1", "berth_3", "berth_4"), 6),
                portState, simClock});

        for (int i = 1; i <= 4; i++) {
            String id = "tug_" + i;
            spawner.spawn(id, TugAgent.class,
                    new Object[] {new TugInitArgs(id, TUG_BASE, 350.0, 2.0, 12.0, 1.0)});
        }

        log.info("Roster spawned: 10 fixed agents (1 HM, 2 terminals, 4 tugs, customs, weather, assistant)");
    }

    /** Calm starting weather + the default Markov matrix, no scripted overrides. */
    private static WeatherInitArgs defaultWeatherInit() {
        return new WeatherInitArgs(
                new WeatherSnapshot(18, "good", 0.5, "sunny", 0L),
                TransitionMatrix.defaults(),
                List.of());
    }
}
