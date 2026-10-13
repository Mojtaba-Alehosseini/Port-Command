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
import it.unige.portcommand.assistant.AutopilotPacing;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.harbourmaster.TugBasePositions;
import it.unige.portcommand.negotiation.NegotiationEngine;
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
        // Pre-S17a signature: no autopilot pacing — a move is sent in the step that decided it.
        spawnSingletonsAndFleet(spawner, portState, simClock, randomSource, marketHistory,
                llmBridge, eventBus, directory, policyRegistry, scenarioRuntime, AutopilotPacing.NONE);
    }

    /**
     * S17a signature (ADR-21, PT12): {@code autopilotPacing} rides the Assistant's args channel,
     * found by type. A launched game passes {@code BootstrapConfig.autopilotPacing()} (about two
     * seconds per move); everything else passes {@link AutopilotPacing#NONE} through the
     * overloads above.
     */
    public static void spawnSingletonsAndFleet(JadeAgentSpawner spawner, PortStateArtifact portState,
                                               SimClock simClock, RandomSource randomSource,
                                               MarketHistoryArtifact marketHistory, LLMBridge llmBridge,
                                               EventBus eventBus, AgentDirectory directory,
                                               PolicyRegistryArtifact policyRegistry,
                                               it.unige.portcommand.scenario.ScenarioRuntime scenarioRuntime,
                                               AutopilotPacing autopilotPacing) {
        spawner.spawn("harbour_master", HarbourMasterAgent.class, new Object[] {
                new HarbourMasterInitArgs(STARTING_WALLET, STARTING_REPUTATION, defaultWeather(), true),
                simClock, randomSource, eventBus, loadContracts(), spawner, marketHistory,
                null, directory, scenarioRuntime});
        spawner.spawn("weather_agent", WeatherAgent.class,
                weatherArgs(randomSource, simClock, eventBus, directory, null, scenarioRuntime));
        spawner.spawn("customs_agent", CustomsAgent.class, new Object[] {randomSource, directory, simClock});
        spawner.spawn("assistant_agent", AssistantAgent.class,
                assistantArgs(marketHistory, policyRegistry, llmBridge, eventBus, directory, simClock,
                        defaultWeather(), autopilotPacing));

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
            // Base position from the canonical TugBasePositions table (one source of truth for
            // the four bases — no duplicated coordinates).
            spawner.spawn(id, TugAgent.class, new Object[] {
                    new TugInitArgs(id, TugBasePositions.position(id), TUG_BASE_FARE,
                            TUG_FUEL_COST_PER_KM, TUG_TOP_SPEED_KNOTS, 1.0),
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
                                       it.unige.portcommand.scenario.ScenarioRuntime scenarioRuntime,
                                       AutopilotPacing autopilotPacing) {
        // The existing task-20 self-future seam at args[7] (adversarial review #3): agent
        // spawning is async, and the loader must not declare the world live before the HM —
        // the coordination anchor and the directory's first entry — finished its setup.
        // Task 23: scenarioRuntime (found by type) re-arms the scripted-event engine with
        // the save's firedEventIds, so a reloaded mid-scenario game never replays.
        spawner.spawn("harbour_master", HarbourMasterAgent.class, new Object[] {
                new HarbourMasterInitArgs(state.wallet(), state.reputation(),
                        state.agents().weather().current(), true),
                simClock, randomSource, eventBus, loadContracts(), spawner, marketHistory,
                hmReady, directory,
                new HarbourMasterRestoreState(state.agents().harbourMaster(),
                        it.unige.portcommand.persistence.GameStateBuilder.reconcileEscorts(state)),
                scenarioRuntime});
        spawner.spawn("weather_agent", WeatherAgent.class,
                weatherArgs(randomSource, simClock, eventBus, directory,
                        state.agents().weather().current(), scenarioRuntime));
        spawner.spawn("customs_agent", CustomsAgent.class, new Object[] {
                randomSource, directory, simClock, state.agents().customs()});
        spawner.spawn("assistant_agent", AssistantAgent.class,
                assistantArgs(marketHistory, policyRegistry, llmBridge, eventBus, directory, simClock,
                        state.agents().weather().current(), autopilotPacing));

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
                    new TugInitArgs(id, TugBasePositions.position(id), TUG_BASE_FARE,
                            TUG_FUEL_COST_PER_KM, TUG_TOP_SPEED_KNOTS, 1.0),
                    portState, simClock, directory, tugDtos.get(id)});
        }

        for (VesselStateDTO dto : state.agents().activeVessels()) {
            VesselSpec spec = new VesselSpec(dto.id(), dto.vesselType(), dto.draft(), dto.length(),
                    dto.tonnage(), dto.cargoClass(), dto.etaArrivalSimMillis(), dto.vesselName());
            if (VesselStateDTO.CHANNEL_WALK_IN.equals(dto.channel())) {
                // A restored walk-in never negotiates again BY DESIGN (open negotiations are
                // not persisted; onRestore concludes the dialogue and attaches no negotiation
                // behaviours — SaveLoadSessionIT pins it). The engine slot nonetheless gets
                // the REAL engine (S7, audit P3-11): the old NoOpNegotiationEngine THREW on
                // evaluate, and JADE kills an agent whose behaviour throws — so any future
                // change that re-attached negotiation behaviours would have produced silently
                // dead vessels instead of negotiations. Same construction as the live spawns;
                // inert today, safe tomorrow.
                spawner.spawn(vesselLocalName(spec), WalkInVesselAgent.class, new Object[] {
                        spec, simClock, marketHistory,
                        new it.unige.portcommand.negotiation.RealNegotiationEngine(
                                it.unige.portcommand.core.Settings.load().roundLimit(),
                                randomSource.forStream("nego-" + dto.id())),
                        randomSource, directory, dto});
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

    /** What a tug charges before distance: the floor of every Contract Net bid. */
    public static final double TUG_BASE_FARE = 350.0;

    /**
     * Euro per km a tug adds to its bid for the run to the ship. S16 (ADR-20): 2.0 x 6 = 12.0,
     * scaled with {@code TugMath.PIXELS_PER_KM} (10 -> 60) so a bid from base to the channel
     * entry is still about EUR 480 - the map got six times smaller in km, the money did not move.
     */
    public static final double TUG_FUEL_COST_PER_KM = 12.0;

    /**
     * Every tug's top speed. Also the speed of the channel leg a ship makes under escort
     * ({@code MovementControl.channelLegSimMillis}): ship and tugs travel together.
     */
    public static final double TUG_TOP_SPEED_KNOTS = 12.0;

    /**
     * The calm reading every free-play game opens on. Public since S15 (2026-10-12):
     * {@code GameStateBuilder.freshSandbox} writes this SAME snapshot into the opening
     * {@code GameState} of a Free play game, so the sandbox booted through the load path
     * ({@code GameSession.startSandbox}) starts under exactly the weather the directly-spawned
     * one ({@code GameSession.startNewGame}) does — one source of truth, no second copy of the
     * four numbers to drift. A fresh record per call; {@code WeatherSnapshot} is immutable.
     */
    public static WeatherSnapshot defaultWeather() {
        return new WeatherSnapshot(18, "good", 0.5, "sunny", 0L);
    }

    /**
     * The weather agent's spawn args, built in ONE place for the fresh and the restore path
     * (S16): the first four by position, then - found by type - the directory, the saved
     * snapshot on a restore, and the scenario's scripted-weather window when it has one
     * ({@code ScriptedWeatherWindow}: random steps are suspended inside it, decision B5). The
     * window is a pure function of the scenario file, so a load passes the same one a fresh
     * start does.
     */
    private static Object[] weatherArgs(RandomSource randomSource, SimClock simClock, EventBus eventBus,
                                        AgentDirectory directory, WeatherSnapshot restoredOrNull,
                                        it.unige.portcommand.scenario.ScenarioRuntime scenarioRuntime) {
        List<Object> args = new java.util.ArrayList<>();
        args.add(defaultWeatherInit());
        args.add(randomSource);
        args.add(simClock);
        args.add(eventBus);
        args.add(directory);
        if (restoredOrNull != null) {
            args.add(restoredOrNull);
        }
        it.unige.portcommand.scenario.ScriptedWeatherWindow
                .of(scenarioRuntime == null ? null : scenarioRuntime.scenario())
                .ifPresent(args::add);
        return args.toArray();
    }

    /** Calm starting weather + the default Markov matrix, no scripted overrides. */
    private static WeatherInitArgs defaultWeatherInit() {
        return new WeatherInitArgs(
                defaultWeather(),
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

    /**
     * The Assistant's spawn args, built in ONE place for the fresh and the restore path (S14):
     * the last entry is the game's conversation clock, which the Hint freeze needs and which
     * {@code AssistantAgent} refuses to boot without. Two hand-copied arrays were one edit away
     * from a load-only bug — the restore path is the one every scenario start and load takes.
     *
     * <p>S16 (B10): and the weather the world opens on, for the Hint's "she will wait outside"
     * note. The bus announces the boot weather once the HarbourMaster is up, which can be before
     * the Assistant has subscribed, and the next broadcast is fifteen game minutes away; handing
     * the reading over here closes that gap. A world with no reading yet passes none.
     *
     * <p>S17a (ADR-21): and how long the autopilot lets a move be seen coming. Found by type like
     * the reading; an Assistant given none does not pace.
     */
    private static Object[] assistantArgs(MarketHistoryArtifact marketHistory,
                                          PolicyRegistryArtifact policyRegistry, LLMBridge llmBridge,
                                          EventBus eventBus, AgentDirectory directory, SimClock simClock,
                                          WeatherSnapshot opening, AutopilotPacing autopilotPacing) {
        List<Object> args = new java.util.ArrayList<>(java.util.Arrays.asList(marketHistory, policyRegistry,
                llmBridge, eventBus, directory, simClock.conversationClock()));
        if (opening != null) {
            args.add(new it.unige.portcommand.harbourmaster.MovementGate.Reading(
                    opening.wind(), opening.visibility(), opening.swell(), opening.state()));
        }
        if (autopilotPacing != null) {
            args.add(autopilotPacing);
        }
        return args.toArray();
    }
}
