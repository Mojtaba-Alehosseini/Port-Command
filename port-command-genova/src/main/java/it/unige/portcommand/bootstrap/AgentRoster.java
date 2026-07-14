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
import it.unige.portcommand.agents.ContractedVesselAgent;
import it.unige.portcommand.agents.WalkInVesselAgent;
import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.artifacts.PolicyRegistryArtifact;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.negotiation.NegotiationEngine;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;
import jade.wrapper.AgentController;
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
    // Tug pier next to the breakwater — four berths stacked vertically (task 08 §8.6).
    private static final Position[] TUG_BASES = {
            new Position(50.0, 100.0, 0.0),
            new Position(50.0, 150.0, 0.0),
            new Position(50.0, 200.0, 0.0),
            new Position(50.0, 250.0, 0.0),
    };

    private AgentRoster() {
    }

    public static void spawnSingletonsAndFleet(JadeAgentSpawner spawner, PortStateArtifact portState,
                                               SimClock simClock, RandomSource randomSource,
                                               MarketHistoryArtifact marketHistory, LLMBridge llmBridge,
                                               EventBus eventBus) {
        spawner.spawn("harbour_master", HarbourMasterAgent.class, null);
        spawner.spawn("weather_agent", WeatherAgent.class,
                new Object[] {defaultWeatherInit(), randomSource, simClock});
        spawner.spawn("customs_agent", CustomsAgent.class, new Object[] {randomSource});
        // A fresh PolicyRegistryArtifact per boot: nothing else reads player autopilot policies
        // yet (task 19's HUD is the first future consumer), so it is not JadeBootstrap-level
        // shared state like portState/marketHistory/eventBus.
        spawner.spawn("assistant_agent", AssistantAgent.class,
                new Object[] {marketHistory, new PolicyRegistryArtifact(), llmBridge, eventBus});

        spawner.spawn("terminal_container", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_container", List.of("berth_2"), 4), portState, simClock});
        spawner.spawn("terminal_general", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_general", List.of("berth_1", "berth_3", "berth_4"), 6),
                portState, simClock});

        for (int i = 1; i <= 4; i++) {
            String id = "tug_" + i;
            // args[1]=portState, args[2]=simClock injected (not the serializable init record) —
            // same channel as the terminals, so the tug can publish position + drive sim-time motion.
            spawner.spawn(id, TugAgent.class, new Object[] {
                    new TugInitArgs(id, TUG_BASES[i - 1], 350.0, 2.0, 12.0, 1.0), portState, simClock});
        }

        log.info("Roster spawned: 10 fixed agents (1 HM, 2 terminals, 4 tugs, customs, weather, assistant)");
    }

    /**
     * Spawn a contracted (Channel A) vessel. Args: {@code [VesselSpec, SimClock,
     * MarketHistoryArtifact, contractId]}.
     */
    public static AgentController spawnContractedVessel(JadeAgentSpawner spawner, VesselSpec spec,
                                                        String contractId, SimClock simClock,
                                                        MarketHistoryArtifact marketHistory) {
        return spawner.spawn(vesselLocalName(spec), ContractedVesselAgent.class,
                new Object[] {spec, simClock, marketHistory, contractId});
    }

    /**
     * Spawn a walk-in (Channel B) vessel. Args: {@code [VesselSpec, SimClock,
     * MarketHistoryArtifact, NegotiationEngine, RandomSource]}. Production passes a
     * {@code NoOpNegotiationEngine}; tests pass a Mockito mock.
     */
    public static AgentController spawnWalkIn(JadeAgentSpawner spawner, VesselSpec spec, SimClock simClock,
                                              MarketHistoryArtifact marketHistory, RandomSource randomSource,
                                              NegotiationEngine engine) {
        return spawner.spawn(vesselLocalName(spec), WalkInVesselAgent.class,
                new Object[] {spec, simClock, marketHistory, engine, randomSource});
    }

    /** A JADE-legal local name ({@code ^[a-z][a-z0-9_]*$}) derived from the vessel id. */
    private static String vesselLocalName(VesselSpec spec) {
        return "vessel_" + spec.vesselId().toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    /** Calm starting weather + the default Markov matrix, no scripted overrides. */
    private static WeatherInitArgs defaultWeatherInit() {
        return new WeatherInitArgs(
                new WeatherSnapshot(18, "good", 0.5, "sunny", 0L),
                TransitionMatrix.defaults(),
                List.of());
    }
}
