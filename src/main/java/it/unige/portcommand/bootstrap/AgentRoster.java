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
import it.unige.portcommand.negotiation.NoOpNegotiationEngine;
import it.unige.portcommand.nlp.LLMBridge;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.AgentDirectory;
import it.unige.portcommand.persistence.GameState;
import it.unige.portcommand.persistence.HarbourMasterRestoreState;
import it.unige.portcommand.persistence.dto.TerminalStateDTO;
import it.unige.portcommand.persistence.dto.TugStateDTO;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
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
        // Pre-task-22 signature, kept for the existing ITs: no live-agent directory, and a
        // fresh PolicyRegistryArtifact per boot (nothing persisted it before task 22).
        spawnSingletonsAndFleet(spawner, portState, simClock, randomSource, marketHistory,
                llmBridge, eventBus, null, new PolicyRegistryArtifact());
    }

    /**
     * Task 22 signature: {@code directory} (nullable) rides the args channel so every agent
     * self-registers for the save snapshot; {@code policyRegistry} is the SESSION-stable
     * instance ({@code JadeBootstrap} owns it since task 22 — the save persists its triggers,
     * so a per-boot instance would silently drop the player's policies on reload).
     */
    public static void spawnSingletonsAndFleet(JadeAgentSpawner spawner, PortStateArtifact portState,
                                               SimClock simClock, RandomSource randomSource,
                                               MarketHistoryArtifact marketHistory, LLMBridge llmBridge,
                                               EventBus eventBus, AgentDirectory directory,
                                               PolicyRegistryArtifact policyRegistry) {
        // Pre-task-23 signature: no scenario runtime → the HM installs no scripted-event
        // engine and no daily contracted stream (the stub-harness ITs' quiet world).
        spawnSingletonsAndFleet(spawner, portState, simClock, randomSource, marketHistory,
                llmBridge, eventBus, directory, policyRegistry, null);
    }

    /**
     * Task 23 signature: {@code scenarioRuntime} (nullable) rides the args channel, found
     * by type — with it the HarbourMaster installs {@code ScriptedEventBehaviour} (the
     * scenario script when one is active, and the {@code ContractSchedule} daily
     * contracted stream in sandbox play too). Every production boot passes one.
     */
    public static void spawnSingletonsAndFleet(JadeAgentSpawner spawner, PortStateArtifact portState,
                                               SimClock simClock, RandomSource randomSource,
                                               MarketHistoryArtifact marketHistory, LLMBridge llmBridge,
                                               EventBus eventBus, AgentDirectory directory,
                                               PolicyRegistryArtifact policyRegistry,
                                               it.unige.portcommand.scenario.ScenarioRuntime scenarioRuntime) {
        spawner.spawn("harbour_master", HarbourMasterAgent.class, new Object[] {
                new HarbourMasterInitArgs(STARTING_WALLET, STARTING_REPUTATION),
                simClock, randomSource, eventBus, loadContracts(), spawner, marketHistory,
                null, directory, scenarioRuntime});
        spawner.spawn("weather_agent", WeatherAgent.class,
                new Object[] {defaultWeatherInit(), randomSource, simClock, eventBus, directory});
        spawner.spawn("customs_agent", CustomsAgent.class, new Object[] {randomSource, directory});
        spawner.spawn("assistant_agent", AssistantAgent.class,
                new Object[] {marketHistory, policyRegistry, llmBridge, eventBus, directory});

        spawner.spawn("terminal_container", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_container", List.of("berth_2"), 4), portState, simClock,
                directory});
        spawner.spawn("terminal_general", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_general", List.of("berth_1", "berth_3", "berth_4"), 6),
                portState, simClock, directory});

        for (int i = 1; i <= 4; i++) {
            String id = "tug_" + i;
            // args[1]=portState, args[2]=simClock injected (not the serializable init record) —
            // same channel as the terminals, so the tug can publish position + drive sim-time motion.
            // Base position from the canonical TugBasePositions table (task 18's map seeds idle
            // dots from the same table — one source of truth, no duplicated coordinates).
            spawner.spawn(id, TugAgent.class, new Object[] {
                    new TugInitArgs(id, TugBasePositions.position(id), 350.0, 2.0, 12.0, 1.0),
                    portState, simClock, directory});
        }

        log.info("Roster spawned: 10 fixed agents (1 HM, 2 terminals, 4 tugs, customs, weather, assistant)");
    }

    /**
     * Task 22: rebuilds the fleet FROM A SAVE. Same 10 fixed agents, in the planning/22
     * load order (HM first so the coordination hub exists before anything greets it;
     * service agents next; restored vessels LAST), each with its persisted DTO appended to
     * the standard args (found by type — the index-based required args are untouched).
     * {@code reconciledTerminals} is the loader's occupancy list already filtered against
     * the restored vessel set (no berth leaks to ghosts).
     */
    public static void respawnFromSave(JadeAgentSpawner spawner, PortStateArtifact portState,
                                       SimClock simClock, RandomSource randomSource,
                                       MarketHistoryArtifact marketHistory, LLMBridge llmBridge,
                                       EventBus eventBus, AgentDirectory directory,
                                       PolicyRegistryArtifact policyRegistry, GameState state,
                                       List<TerminalStateDTO> reconciledTerminals,
                                       java.util.concurrent.CompletableFuture<HarbourMasterAgent> hmReady,
                                       it.unige.portcommand.scenario.ScenarioRuntime scenarioRuntime) {
        // The existing task-20 self-future seam at args[7] (adversarial review #3): agent
        // spawning is async, and the loader must not declare the world live before the HM —
        // the coordination anchor and the directory's first entry — finished its setup.
        // Task 23: scenarioRuntime (found by type) re-arms the scripted-event engine with
        // the save's firedEventIds, so a reloaded mid-scenario game never replays.
        spawner.spawn("harbour_master", HarbourMasterAgent.class, new Object[] {
                new HarbourMasterInitArgs(state.wallet(), state.reputation()),
                simClock, randomSource, eventBus, loadContracts(), spawner, marketHistory,
                hmReady, directory,
                new HarbourMasterRestoreState(state.agents().harbourMaster(),
                        state.agents().activeVessels()),
                scenarioRuntime});
        spawner.spawn("weather_agent", WeatherAgent.class, new Object[] {
                defaultWeatherInit(), randomSource, simClock, eventBus, directory,
                state.agents().weather().current()});
        spawner.spawn("customs_agent", CustomsAgent.class, new Object[] {
                randomSource, directory, state.agents().customs()});
        spawner.spawn("assistant_agent", AssistantAgent.class,
                new Object[] {marketHistory, policyRegistry, llmBridge, eventBus, directory});

        spawner.spawn("terminal_container", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_container", List.of("berth_2"), 4), portState, simClock,
                directory, terminalDto(reconciledTerminals, "terminal_container")});
        spawner.spawn("terminal_general", TerminalAgent.class, new Object[] {
                new TerminalInitArgs("terminal_general", List.of("berth_1", "berth_3", "berth_4"), 6),
                portState, simClock, directory, terminalDto(reconciledTerminals, "terminal_general")});

        Map<String, TugStateDTO> tugDtos = state.agents().tugs().stream()
                .collect(Collectors.toMap(TugStateDTO::tugId, t -> t));
        for (int i = 1; i <= 4; i++) {
            String id = "tug_" + i;
            spawner.spawn(id, TugAgent.class, new Object[] {
                    new TugInitArgs(id, TugBasePositions.position(id), 350.0, 2.0, 12.0, 1.0),
                    portState, simClock, directory, tugDtos.get(id)});
        }

        for (VesselStateDTO dto : state.agents().activeVessels()) {
            VesselSpec spec = new VesselSpec(dto.id(), dto.vesselType(), dto.draft(), dto.length(),
                    dto.tonnage(), dto.cargoClass(), dto.etaArrivalSimMillis());
            if (VesselStateDTO.CHANNEL_WALK_IN.equals(dto.channel())) {
                // A restored walk-in never negotiates again (open negotiations are not
                // persisted) — the engine slot is filled with the no-op binding.
                spawner.spawn(vesselLocalName(spec), WalkInVesselAgent.class, new Object[] {
                        spec, simClock, marketHistory, new NoOpNegotiationEngine(), randomSource,
                        directory, dto});
            } else {
                spawner.spawn(vesselLocalName(spec), ContractedVesselAgent.class, new Object[] {
                        spec, simClock, marketHistory,
                        dto.contractRef() == null ? "restored" : dto.contractRef(), directory, dto});
            }
        }

        log.info("Roster respawned from save: 10 fixed agents + {} restored vessel(s)",
                state.agents().activeVessels().size());
    }

    private static TerminalStateDTO terminalDto(List<TerminalStateDTO> terminals, String terminalId) {
        return terminals.stream().filter(t -> terminalId.equals(t.terminalId())).findFirst()
                .orElse(new TerminalStateDTO(terminalId, List.of()));
    }

    /**
     * Spawn a contracted (Channel A) vessel. Args: {@code [VesselSpec, SimClock,
     * MarketHistoryArtifact, contractId]}.
     */
    public static AgentController spawnContractedVessel(JadeAgentSpawner spawner, VesselSpec spec,
                                                        String contractId, SimClock simClock,
                                                        MarketHistoryArtifact marketHistory) {
        return spawnContractedVessel(spawner, spec, contractId, simClock, marketHistory, null);
    }

    /** Task 22 overload: {@code directory} (nullable) appended so contracted vessels register
     * for the save snapshot — without it a save cannot capture their flow position (task 23's
     * live spawner must use this form). */
    public static AgentController spawnContractedVessel(JadeAgentSpawner spawner, VesselSpec spec,
                                                        String contractId, SimClock simClock,
                                                        MarketHistoryArtifact marketHistory,
                                                        AgentDirectory directory) {
        return spawner.spawn(vesselLocalName(spec), ContractedVesselAgent.class,
                new Object[] {spec, simClock, marketHistory, contractId, directory});
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
        return spawnWalkIn(spawner, spec, simClock, marketHistory, randomSource, engine, null);
    }

    /** Task 22 overload: {@code directory} (nullable) appended so live-spawned walk-ins
     * self-register and are captured by the save snapshot like every other agent. */
    public static AgentController spawnWalkIn(JadeAgentSpawner spawner, VesselSpec spec, SimClock simClock,
                                              MarketHistoryArtifact marketHistory, RandomSource randomSource,
                                              NegotiationEngine engine, AgentDirectory directory) {
        return spawnWalkIn(spawner, spec, simClock, marketHistory, randomSource, engine, directory, null);
    }

    /** Task 23 overload: {@code overrides} (nullable) pins scenario-scripted hidden
     * beliefs; null fields (or a null record) keep the normal template draws. Still
     * in-JVM-only — the beliefs never surface (P-04). */
    public static AgentController spawnWalkIn(JadeAgentSpawner spawner, VesselSpec spec, SimClock simClock,
                                              MarketHistoryArtifact marketHistory, RandomSource randomSource,
                                              NegotiationEngine engine, AgentDirectory directory,
                                              it.unige.portcommand.negotiation.WalkInBeliefOverrides overrides) {
        return spawner.spawn(vesselLocalName(spec), WalkInVesselAgent.class,
                new Object[] {spec, simClock, marketHistory, engine, randomSource, directory, overrides});
    }

    /** A JADE-legal local name ({@code ^[a-z][a-z0-9_]*$}) derived from the vessel id. Public
     * since task 22: the HarbourMaster's tracking restore and the loader both rebuild AIDs
     * from this SAME derivation — determinism of the name is what makes that legal. */
    public static String vesselLocalName(VesselSpec spec) {
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
     * resource is non-fatal (empty map, WARN logged). Public since task 23:
     * {@code ScenarioLoader} validates contracted spawn refs against this same set,
     * and these entries ARE the daily contracted stream's base schedule
     * ({@code scenario.ContractSchedule}).
     */
    public static Map<String, ServiceContract> loadContracts() {
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
