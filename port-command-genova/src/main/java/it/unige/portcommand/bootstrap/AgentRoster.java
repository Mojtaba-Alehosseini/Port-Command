package it.unige.portcommand.bootstrap;

import java.util.List;

import it.unige.portcommand.agents.AssistantAgent;
import it.unige.portcommand.agents.CustomsAgent;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.agents.InitArgs.TerminalInitArgs;
import it.unige.portcommand.agents.InitArgs.TugInitArgs;
import it.unige.portcommand.agents.TerminalAgent;
import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.WeatherAgent;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.util.SimClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single place that constructs the fixed agent fleet: the four singletons
 * (HarbourMaster, Weather, Customs, Assistant), the two terminals, and the four
 * tugs — 10 fixed instances. Vessel agents are spawned dynamically elsewhere.
 *
 * <p>The terminals receive the shared {@link PortStateArtifact} and {@link SimClock}
 * through the {@code Object[]} args channel (args[1], args[2]) — live objects, not
 * serializable init records, so constructor-injected rather than via a static.
 *
 * <p>No spawn stagger: each agent registers with the DF asynchronously on its own
 * thread, so ten near-simultaneous {@code setup()} calls do not contend.
 */
public final class AgentRoster {

    private static final Logger log = LoggerFactory.getLogger(AgentRoster.class);
    private static final Position TUG_BASE = new Position(0.0, 0.0, 0.0);

    private AgentRoster() {
    }

    public static void spawnSingletonsAndFleet(JadeAgentSpawner spawner, PortStateArtifact portState,
                                               SimClock simClock) {
        spawner.spawn("harbour_master", HarbourMasterAgent.class, null);
        spawner.spawn("weather_agent", WeatherAgent.class, null);
        spawner.spawn("customs_agent", CustomsAgent.class, null);
        spawner.spawn("assistant_agent", AssistantAgent.class, null);

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
}
