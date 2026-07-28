package it.unige.portcommand.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.behaviours.RestoreDispatchBehaviour;
import it.unige.portcommand.behaviours.SimClockTickPublisherBehaviour;
import it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour;
import it.unige.portcommand.behaviours.coordination.DispatchPlayerCommandBehaviour;
import it.unige.portcommand.behaviours.coordination.EndOfDayDetectBehaviour;
import it.unige.portcommand.behaviours.coordination.ForwardWalkInToPlayerBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleEmergencyBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleWeatherAlertBehaviour;
import it.unige.portcommand.behaviours.coordination.PoissonSpawnBehaviour;
import it.unige.portcommand.bootstrap.AgentRoster;
import it.unige.portcommand.bootstrap.JadeAgentSpawner;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.commlog.Paraphraser;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.harbourmaster.CnpCoordinator;
import it.unige.portcommand.harbourmaster.RealCnpCoordinator;
import it.unige.portcommand.harbourmaster.ReputationLedger;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.harbourmaster.financial.LedgerCoordinator;
import it.unige.portcommand.harbourmaster.financial.PerformativeCounter;
import it.unige.portcommand.lifecycle.DayRolloverCoordinator;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.persistence.HarbourMasterRestoreState;
import it.unige.portcommand.persistence.dto.VesselStateDTO;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Central coordinator and the player's proxy (there is no {@code PlayerAgent},
 * CLAUDE.md rule 2). Dispatches the contracted auto-flow, relays walk-in
 * negotiations to the player, translates player commands into ACL messages,
 * arbitrates the tug Contract Net, holds vessels on unsafe weather/customs flags,
 * detects end-of-day, and drives Poisson-timed walk-in spawns. Every binding
 * decision is gated through {@link it.unige.portcommand.prolog.PrologQueries} first
 * (CLAUDE.md rule 4) — see each behaviour's own javadoc for its specific gate.
 * Every behaviour's ACL traffic is also the comm log's single source (task 17):
 * {@link #sendLogged(ACLMessage)} / {@link #receiveLogged(MessageTemplate)} wrap the
 * (final, unoverridable) {@code send}/{@code receive} to publish a {@code CommLogEvent}
 * alongside every message this hub sends or receives.
 *
 * <p>{@code activeVessels}/{@code pendingCustomsChecks}/{@code negotiationBerths}/
 * {@code negotiationRounds} are {@link ConcurrentHashMap}s shared read/write across
 * several behaviours (planning/11's Risks section calls out {@code activeVessels}
 * concurrency explicitly). {@code customsAid} is resolved lazily (never eagerly at
 * {@link #onSetup()}) because DF registration across the 10-agent fleet is async —
 * the HarbourMaster may finish its own setup before Customs registers; it is used
 * only to ADDRESS the pre-clearance REQUEST, never to match the reply (every
 * inbound-message template in this hub routes by protocol tag, not sender AID —
 * see {@code HandleEmergencyBehaviour}/{@code HandleWeatherAlertBehaviour}'s
 * javadoc for why an AID-based design was replaced, adversarial review finding).
 */
public final class HarbourMasterAgent extends PortCommandAgent
        implements it.unige.portcommand.persistence.Mementoable {

    private final ConcurrentHashMap<String, VesselTracking> activeVessels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pendingCustomsChecks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> negotiationBerths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> negotiationRounds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> cnpRetryAttempts = new ConcurrentHashMap<>();

    private WalletLedger walletLedger;
    private ReputationLedger reputationLedger;
    private LedgerCoordinator ledgerCoordinator;
    private PerformativeCounter performativeCounter;
    private DayRolloverCoordinator dayRolloverCoordinator;
    private SimClock simClock;
    private RandomSource randomSource;
    private EventBus eventBus;
    private Map<String, ServiceContract> contracts;
    private CnpCoordinator cnpCoordinator;
    private volatile AID customsAid;
    /** Task 22: held so takedown can cancel its bus subscription and the snapshot can read
     * the walk-in spawn counter. */
    private DispatchPlayerCommandBehaviour dispatchPlayerCommand;
    private PoissonSpawnBehaviour poissonSpawn;
    /** Task 23: the scenario engine + daily contracted stream; null only in stub harnesses
     * that pass no {@code ScenarioRuntime}. Snapshot reads it for {@code firedEventIds}. */
    private it.unige.portcommand.scenario.ScriptedEventBehaviour scriptedEvents;

    @Override
    protected void registerServices() {
        registerDfService("harbour-master", getLocalName());
    }

    @Override
    protected void onSetup() {
        HarbourMasterInitArgs init = initArg(HarbourMasterInitArgs.class);
        if (init == null) {
            throw new IllegalStateException(getLocalName() + " requires HarbourMasterInitArgs at args[0]");
        }
        this.simClock = argAt(1, SimClock.class);
        this.randomSource = argAt(2, RandomSource.class);
        this.eventBus = argAt(3, EventBus.class);
        // Ledgers are constructed AFTER eventBus (task 20): they publish Wallet/ReputationChanged
        // on every mutation, which is the HUD's live data path.
        this.walletLedger = new WalletLedger(init.startingWallet(), eventBus);
        this.reputationLedger = new ReputationLedger(init.startingReputation(), eventBus);
        // Plain bus subscribers, not JADE behaviours — the catalogue stays locked at 51 (ADR-01).
        this.ledgerCoordinator = new LedgerCoordinator(eventBus, walletLedger, reputationLedger);
        this.performativeCounter = new PerformativeCounter(eventBus);
        // Task 24: the EOD owner. Constructed here (LedgerCoordinator precedent) because every
        // collaborator is agent-owned; it subscribes to EndOfDayDetectBehaviour's EndOfDayEvent.
        this.dayRolloverCoordinator = new DayRolloverCoordinator(eventBus, simClock, walletLedger,
                reputationLedger, performativeCounter);
        // Task 23: mutable since the scenario engine registers scenario-local contracts and
        // the daily stream's day-qualified clones at runtime (ConcurrentHashMap — behaviours
        // read it on this thread, but the map is advertised shared state like the trackers).
        this.contracts = new ConcurrentHashMap<>(contractsArg());
        JadeAgentSpawner spawner = argAt(5, JadeAgentSpawner.class);
        MarketHistoryArtifact marketHistory = argAt(6, MarketHistoryArtifact.class);
        this.cnpCoordinator = new RealCnpCoordinator(this, serviceLocator(), simClock);

        // Task 22 restore: the loader appends a HarbourMasterRestoreState (found by type).
        // Ledger BALANCES arrived via HarbourMasterInitArgs; here the in-progress day's
        // history, counters, pending customs and tracking entries are seeded, and the
        // dispatcher below re-drives what the save interrupted.
        HarbourMasterRestoreState restore = optionalArgOfType(HarbourMasterRestoreState.class);
        int startingSpawnCount = 0;
        if (restore != null) {
            walletLedger.restoreHistory(restore.harbourMaster().dayIncome(),
                    restore.harbourMaster().dayExpense());
            performativeCounter.restore(restore.harbourMaster().performativeCounts());
            pendingCustomsChecks.putAll(restore.harbourMaster().pendingCustomsChecks());
            startingSpawnCount = restore.harbourMaster().walkInSpawnCount();
            restoreTracking(restore.activeVessels());
        }

        addBehaviour(new AutoFlowDispatcherBehaviour(this));
        addBehaviour(new ForwardWalkInToPlayerBehaviour(this));
        this.dispatchPlayerCommand = new DispatchPlayerCommandBehaviour(this);
        addBehaviour(dispatchPlayerCommand);
        addBehaviour(new HandleEmergencyBehaviour(this));
        addBehaviour(new HandleWeatherAlertBehaviour(this));
        addBehaviour(new EndOfDayDetectBehaviour(this, simClock));
        this.poissonSpawn = new PoissonSpawnBehaviour(this, simClock, randomSource, spawner,
                marketHistory, startingSpawnCount);
        addBehaviour(poissonSpawn);
        // Task 24: the single SimClockTickEvent publisher (Option B) — uncounted parent-package
        // behaviour, so the 51-catalogue is untouched.
        addBehaviour(new SimClockTickPublisherBehaviour(this, simClock, eventBus));
        if (restore != null) {
            addBehaviour(new RestoreDispatchBehaviour(this));
        }

        // Task 23: the scenario engine (scenario-package behaviour — catalogue stays 51,
        // ADR-01/INVARIANTS). The daily contracted stream's base is captured from the
        // PRISTINE contracts.json arg BEFORE scenario-local contracts merge in, so script
        // props never recur daily. Installed on every production boot (ScenarioRuntime is
        // threaded by sandbox, scenario and load paths alike); stub harnesses without a
        // runtime get no engine and keep their quiet world.
        it.unige.portcommand.scenario.ScenarioRuntime scenarioRuntime =
                optionalArgOfType(it.unige.portcommand.scenario.ScenarioRuntime.class);
        if (scenarioRuntime != null) {
            java.util.List<ServiceContract> dailyBase = java.util.List.copyOf(contractsArg().values());
            if (scenarioRuntime.scenario() != null) {
                for (ServiceContract local : scenarioRuntime.scenario().contracts()) {
                    contracts.putIfAbsent(local.contractId(), local);
                }
            }
            this.scriptedEvents = new it.unige.portcommand.scenario.ScriptedEventBehaviour(this,
                    new it.unige.portcommand.scenario.GameContext(contracts, simClock, spawner,
                            marketHistory, randomSource, eventBus, agentDirectory()),
                    scenarioRuntime, dailyBase);
            addBehaviour(scriptedEvents);
        }

        completeOptionalSelfFuture();
    }

    /**
     * Rebuilds {@link #activeVessels} from persisted DTOs (task 22). AIDs are reconstructed
     * from the DETERMINISTIC vessel local names ({@code AgentRoster.vesselLocalName}) —
     * resolution happens at send time inside the live container, so the entries are valid
     * before the vessel agents themselves finish spawning.
     */
    private void restoreTracking(java.util.List<VesselStateDTO> vessels) {
        for (VesselStateDTO dto : vessels) {
            VesselSpec spec = new VesselSpec(dto.id(), dto.vesselType(), dto.draft(), dto.length(),
                    dto.tonnage(), dto.cargoClass(), dto.etaArrivalSimMillis());
            VesselTracking.Channel channel = VesselStateDTO.CHANNEL_WALK_IN.equals(dto.channel())
                    ? VesselTracking.Channel.WALK_IN : VesselTracking.Channel.CONTRACTED;
            AID aid = new AID(AgentRoster.vesselLocalName(spec), AID.ISLOCALNAME);
            activeVessels.put(dto.id(), new VesselTracking(dto.id(), spec, channel,
                    VesselTracking.Stage.valueOf(dto.stage()), aid, dto.assignedBerth(),
                    dto.assignedTugs(), dto.blocked(), dto.weatherHeld()));
        }
        log.info("restored {} tracked vessel(s)", vessels.size());
    }

    /**
     * Unsubscribes the task-20 bus consumers. Load-bearing, not housekeeping: the {@code EventBus}
     * is {@code JadeBootstrap}-level shared state that OUTLIVES this agent, so a HarbourMaster
     * respawn on the same bus would otherwise leave the previous agent's {@link LedgerCoordinator}
     * subscribed alongside the new one — and both would charge every event, each with its own
     * independent dedupe sets, which cannot catch a duplicate they never saw. That is exactly the
     * teardown/rebuild task 22 (save/load) performs and task 26 (3 scenarios) repeats. Found by the
     * task-20 adversarial review as a latent double-charge; unreachable today (one HM per JVM).
     */
    @Override
    protected void onTakeDown() {
        if (dayRolloverCoordinator != null) {
            dayRolloverCoordinator.close();
        }
        if (ledgerCoordinator != null) {
            ledgerCoordinator.close();
        }
        if (performativeCounter != null) {
            performativeCounter.close();
        }
        // Task 22: the player-command dispatcher subscribes-once on the shared bus; without
        // this cancel a respawned HM would double-dispatch every command (and the dead
        // handler would double-count comm-log traffic through the new PerformativeCounter).
        if (dispatchPlayerCommand != null) {
            dispatchPlayerCommand.cancelSubscription();
        }
    }

    /**
     * The HM-owned slice of the save file (task 22): the IN-PROGRESS day's ledger entries
     * (what the next EOD aggregates), today's performative tally, pending customs
     * conversations (sorted — the serialized-map determinism rule) and the walk-in spawn
     * counter. Wallet balance and reputation live at the {@code GameState} root.
     */
    public it.unige.portcommand.persistence.dto.HarbourMasterStateDTO snapshotDto() {
        int day = SimClock.gameDayOf(simClock.nowSimMillis());
        java.util.List<it.unige.portcommand.harbourmaster.IncomeEvent> dayIncome =
                walletLedger.incomeHistory().stream()
                        .filter(e -> SimClock.gameDayOf(e.simTime()) == day)
                        .toList();
        java.util.List<it.unige.portcommand.harbourmaster.ExpenseEvent> dayExpense =
                walletLedger.expenseHistory().stream()
                        .filter(e -> SimClock.gameDayOf(e.simTime()) == day)
                        .toList();
        java.util.Map<String, String> pendingSorted = new java.util.LinkedHashMap<>();
        pendingCustomsChecks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> pendingSorted.put(e.getKey(), e.getValue()));
        return new it.unige.portcommand.persistence.dto.HarbourMasterStateDTO(
                dayIncome, dayExpense, performativeCounter.snapshot(), pendingSorted,
                poissonSpawn == null ? 0 : poissonSpawn.spawnCount());
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode snapshot() {
        return persistenceMapper().valueToTree(snapshotDto());
    }

    /** Restore flows through the args channel ({@code HarbourMasterRestoreState}) at setup;
     * this live entry point exists for {@code Mementoable} completeness and tests. */
    @Override
    public void restore(com.fasterxml.jackson.databind.JsonNode node) {
        var dto = persistenceMapper().convertValue(node,
                it.unige.portcommand.persistence.dto.HarbourMasterStateDTO.class);
        walletLedger.restoreHistory(dto.dayIncome(), dto.dayExpense());
        performativeCounter.restore(dto.performativeCounts());
        pendingCustomsChecks.putAll(dto.pendingCustomsChecks());
    }

    /**
     * Test-only seam (optional args[7], never passed by {@code AgentRoster}):
     * publishes {@code this} — a fully container-bound, {@code send()}-capable
     * reference — to a test thread once setup finishes. Mirrors
     * {@code JadeBootstrap}'s own {@code CountDownLatch}/{@code CompletableFuture}
     * pattern for {@code BootstrapProbeAgent}. An {@code AgentController} alone
     * cannot do this (JADE deliberately hides the underlying {@code Agent}), and a
     * hand-constructed {@code Agent} can't call {@code send()} (no container
     * binding) — this is the one place a test can get a real, usable reference to
     * seed {@link #activeVessels()} directly or invoke a behaviour's public handler
     * without needing a real subscriber round-trip on the shared EventBus.
     */
    @SuppressWarnings("unchecked")
    private void completeOptionalSelfFuture() {
        Object[] args = getArguments();
        if (args != null && args.length > 7 && args[7] != null) {
            ((CompletableFuture<HarbourMasterAgent>) args[7]).complete(this);
        }
    }

    private Object requireArg(int index, String what) {
        Object[] args = getArguments();
        if (args == null || args.length <= index || args[index] == null) {
            throw new IllegalStateException(getLocalName() + " requires " + what + " at args[" + index + "]");
        }
        return args[index];
    }

    private <T> T argAt(int index, Class<T> type) {
        return type.cast(requireArg(index, type.getSimpleName()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, ServiceContract> contractsArg() {
        return (Map<String, ServiceContract>) requireArg(4, "contracts map");
    }

    // --- shared state, read/written by the coordination behaviours ---

    public ConcurrentHashMap<String, VesselTracking> activeVessels() {
        return activeVessels;
    }

    public ConcurrentHashMap<String, String> pendingCustomsChecks() {
        return pendingCustomsChecks;
    }

    public ConcurrentHashMap<String, String> negotiationBerths() {
        return negotiationBerths;
    }

    public ConcurrentHashMap<String, AtomicInteger> negotiationRounds() {
        return negotiationRounds;
    }

    /** Zero-bid CNP retry counter per vessel (task 15's {@code CnpRetryBehaviour} seam). */
    public ConcurrentHashMap<String, AtomicInteger> cnpRetryAttempts() {
        return cnpRetryAttempts;
    }

    /** Live contract map: {@code contracts.json} + scenario-locals + the daily stream's
     * day-qualified clones (task 23 — mutable by the scenario engine only). */
    public Map<String, ServiceContract> contracts() {
        return contracts;
    }

    /** The scenario engine (task 23), or null in stub harnesses without a
     * {@code ScenarioRuntime}. Read by {@code GameStateBuilder} for the save's
     * {@code activeScenario}/{@code firedEventIds} and by the Poisson gate below. */
    public it.unige.portcommand.scenario.ScriptedEventBehaviour scriptedEvents() {
        return scriptedEvents;
    }

    /** True while an active scenario's scripted wave is still firing — the Poisson
     * spawner suppression gate (planning/23: resume after the last scripted event). */
    public boolean scriptedWaveActive() {
        return scriptedEvents != null && scriptedEvents.scenarioWaveActive();
    }

    public CnpCoordinator cnpCoordinator() {
        return cnpCoordinator;
    }

    public SimClock simClock() {
        return simClock;
    }

    public RandomSource randomSource() {
        return randomSource;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public WalletLedger walletLedger() {
        return walletLedger;
    }

    /**
     * The bus-fed ledger writer (task 20). Held so the subscriptions outlive setup and so tests
     * can drive its handlers directly, the task-10 subscribe-once precedent.
     */
    public LedgerCoordinator ledgerCoordinator() {
        return ledgerCoordinator;
    }

    /**
     * The day's FIPA performative tally (task 20) — {@code DayRolloverCoordinator} (task 24) reads
     * it for the EOD summary. Prefer its {@code resetForNewDay()} return over snapshot-then-reset;
     * see {@link PerformativeCounter}'s javadoc for why.
     */
    public PerformativeCounter performativeCounter() {
        return performativeCounter;
    }

    public ReputationLedger reputationLedger() {
        return reputationLedger;
    }

    /** The task-24 EOD owner — exposed so integration tests can drive/inspect it directly. */
    public DayRolloverCoordinator dayRolloverCoordinator() {
        return dayRolloverCoordinator;
    }

    /**
     * Public wrapper — {@code PortCommandAgent.getServiceLocator()} is protected,
     * and the coordination behaviours that need it live in a different package
     * (same pattern as {@code BaseVesselAgent.serviceLocator()}).
     */
    public ServiceLocator serviceLocator() {
        return getServiceLocator();
    }

    /** Public wrapper for the same cross-package reason: {@code PoissonSpawnBehaviour}
     * threads the session's live-agent registry into every walk-in spawn (task 22). */
    public it.unige.portcommand.persistence.AgentDirectory directory() {
        return agentDirectory();
    }

    /**
     * Lazily resolves + caches the CustomsAgent's AID (DF registration is async
     * across the fleet). Used only to ADDRESS the pre-clearance REQUEST
     * ({@code AutoFlowDispatcherBehaviour}) — matching the reply is done by
     * protocol tag, not this AID (see {@code HandleEmergencyBehaviour}'s javadoc).
     */
    public AID resolveCustomsAid() {
        if (customsAid == null) {
            getServiceLocator().findUnique("customs").ifPresent(aid -> customsAid = aid);
        }
        return customsAid;
    }

    /**
     * Sends {@code message}, first publishing a {@link CommLogEvent} for it (task 17 §BUILD
     * 4 — the HarbourMaster's send/receive path is the comm-log's single instrumentation
     * point). {@code jade.core.Agent.send} is {@code final} (javap-verified against
     * jade-4.6.0.jar — cannot be overridden), so every HM behaviour that used to call
     * {@code myAgent.send(...)} directly now routes through this wrapper instead. Logged
     * BEFORE the real send so a slow EventBus subscriber can never delay the wire send.
     */
    public void sendLogged(ACLMessage message) {
        publishCommLog(message, getLocalName());
        send(message);
    }

    /**
     * Non-blocking poll variant of {@code receive(template)} (same contract: returns the next
     * matching message or {@code null} immediately) that publishes a {@link CommLogEvent} for
     * whatever it returns. Every HM/CNP behaviour that used to call
     * {@code myAgent.receive(template)} directly now routes through this wrapper instead. See
     * {@link #sendLogged(ACLMessage)}'s javadoc for why a wrapper (not an override) is needed.
     */
    public ACLMessage receiveLogged(MessageTemplate template) {
        ACLMessage message = receive(template);
        if (message != null) {
            AID senderAid = message.getSender();
            publishCommLog(message, senderAid != null ? senderAid.getLocalName() : "unknown");
        }
        return message;
    }

    private void publishCommLog(ACLMessage message, String sender) {
        List<String> receivers = new ArrayList<>();
        var it = message.getAllReceiver();
        while (it.hasNext()) {
            receivers.add(((AID) it.next()).getLocalName());
        }
        eventBus.publish(new CommLogEvent(simClock.nowSimMillis(), sender, receivers,
                message.getPerformative(), Paraphraser.paraphrase(message, sender), message.getConversationId()));
    }
}
