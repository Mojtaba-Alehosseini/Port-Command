package it.unige.portcommand.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.agents.AssistantAgent;
import it.unige.portcommand.agents.CustomsAgent;
import it.unige.portcommand.agents.HarbourMasterAgent;
import it.unige.portcommand.agents.HarbourMasterInitArgs;
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
import it.unige.portcommand.harbourmaster.TugBasePositions;
import it.unige.portcommand.negotiation.NegotiationEngine;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.ontology.ServiceContract;
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
    /**
     * The HarbourMaster's starting wallet/reputation. Public since task 24: {@code Main}
     * seeds {@code GameOverGuard}'s pre-first-event fallback stats from the SAME constants
     * that feed {@code HarbourMasterInitArgs}, so a quit-before-any-money game-over reports
     * the true opening figures rather than a second, driftable copy.
     */
    public static final double STARTING_WALLET = 50_000.0;
    public static final double STARTING_REPUTATION = 70.0;

    private AgentRoster() {
    }

    public static void spawnSingletonsAndFleet(JadeAgentSpawner spawner, PortStateArtifact portState,
                                               SimClock simClock, RandomSource randomSource,
                                               MarketHistoryArtifact marketHistory, LLMBridge llmBridge,
                                               EventBus eventBus) {
        spawner.spawn("harbour_master", HarbourMasterAgent.class, new Object[] {
                new HarbourMasterInitArgs(STARTING_WALLET, STARTING_REPUTATION),
                simClock, randomSource, eventBus, loadContracts(), spawner, marketHistory});
        spawner.spawn("weather_agent", WeatherAgent.class,
                new Object[] {defaultWeatherInit(), randomSource, simClock, eventBus});
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
            // Base position from the canonical TugBasePositions table (task 18's map seeds idle
            // dots from the same table — one source of truth, no duplicated coordinates).
            spawner.spawn(id, TugAgent.class, new Object[] {
                    new TugInitArgs(id, TugBasePositions.position(id), 350.0, 2.0, 12.0, 1.0),
                    portState, simClock});
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
     * {@code RealNegotiationEngine} (task 15, constructed per-vessel by
     * {@code PoissonSpawnBehaviour}); tests pass a Mockito mock or their own engine instance.
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

    /**
     * Loads {@code data/contracts.json} once at startup (planning/11 §11.2 step 1),
     * keyed by {@code contractId}. A test constructs its own small map directly
     * instead of calling this (constructor-arg DI — no static registry). Missing
     * resource is non-fatal (empty map, WARN logged): the fixed demo contracts are
     * placeholder data superseded by task 23's scenario loader.
     */
    private static Map<String, ServiceContract> loadContracts() {
        try (InputStream in = AgentRoster.class.getResourceAsStream("/data/contracts.json")) {
            if (in == null) {
                log.warn("data/contracts.json not found on classpath; HarbourMaster boots with zero contracts");
                return Map.of();
            }
            List<ServiceContract> contracts = new ObjectMapper()
                    .readValue(in, new TypeReference<List<ServiceContract>>() { });
            return contracts.stream()
                    .collect(Collectors.toMap(ServiceContract::contractId, c -> c));
        } catch (IOException e) {
            throw new IllegalStateException("failed to load data/contracts.json", e);
        }
    }
}
