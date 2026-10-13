package it.unige.portcommand.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
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
import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.harbourmaster.CnpCoordinator;
import it.unige.portcommand.harbourmaster.PortStatusSnapshot;
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
import it.unige.portcommand.behaviours.cnp.InitiateCNPBehaviour;

/**
 * Central coordinator and the player's proxy (there is no {@code PlayerAgent},
 * design rule 2). Dispatches the contracted auto-flow, relays walk-in
 * negotiations to the player, translates player commands into ACL messages,
 * arbitrates the tug Contract Net, holds vessels on unsafe weather/customs flags,
 * detects end-of-day, and drives Poisson-timed walk-in spawns. Every binding
 * decision is gated through {@link it.unige.portcommand.prolog.PrologQueries} first
 * (design rule 4) — see each behaviour's own javadoc for its specific gate.
 * Every behaviour's ACL traffic is also the comm log's single source (task 17):
 * {@link #sendLogged(ACLMessage)} / {@link #receiveLogged(MessageTemplate)} wrap the
 * (final, unoverridable) {@code send}/{@code receive} to publish a {@code CommLogEvent}
 * alongside every message this hub sends or receives.
 *
 * <p>{@code activeVessels}/{@code pendingCustomsChecks}/{@code negotiationBerths}/
 * {@code negotiationRounds}/{@code negotiationPlayerOffers}
 * are {@link ConcurrentHashMap}s shared read/write across
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
    private final ConcurrentHashMap<String, Double> negotiationPlayerOffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> cnpRetryAttempts = new ConcurrentHashMap<>();
    /** S5: parked grants awaiting a free berth. HM-thread confined; see BerthWaitQueue's javadoc. */
    private final it.unige.portcommand.harbourmaster.BerthWaitQueue berthWaitQueue =
            new it.unige.portcommand.harbourmaster.BerthWaitQueue();
    /** S5: queued CONTRACTED grants' vesselId → contractId, so the release sweep can re-run the
     * contracted grant pipeline (pinned berth, fee event, terminal + customs REQUESTs). */
    private final ConcurrentHashMap<String, String> pendingContractGrants = new ConcurrentHashMap<>();
    /**
     * S5: runtime priority escalations, vesselId → class atom ({@code emergency}/{@code medical}).
     * The honest producer is the weather cascade — a vessel found UNSAFE in transit IS the
     * emergency (driven by the R15-R19 verdict, not invented data). Passed per query into
     * {@code PrologQueries.effectivePriority}; the dynamic Prolog fact exists only transiently
     * inside that call. Cleared at re-dispatch (paired with {@code weatherHeld}) and at departure.
     */
    private final ConcurrentHashMap<String, String> priorityOverrides = new ConcurrentHashMap<>();
    /**
     * S5 (Goal B): the last weather reading this hub has seen — written by
     * {@code HandleWeatherAlertBehaviour} on every alert AND every 15-sim-min periodic snapshot,
     * read by the player-command Prolog gate. A single volatile record, null until the first
     * reading arrives (the gate then checks compatibility only — bootstrap grace).
     */
    private volatile WeatherReading lastWeather;

    /** The four fields every weather message carries, as one immutable cell. */
    public record WeatherReading(double windKnots, String visibility, double swell, String state) {
    }

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
    /** S8 (GOAL 3, ADR-17): the session's name-pool offset — rolled once per new game from the
     * seeded {@code "name-pool"} stream, restored verbatim from a save. Read by
     * {@code PoissonSpawnBehaviour.spawnWalkIn} and persisted by {@link #snapshotDto()}. */
    private int namePoolOffset;
    /** Task 23: the scenario engine + daily contracted stream; null only in stub harnesses
     * that pass no {@code ScenarioRuntime}. Snapshot reads it for {@code firedEventIds}. */
    private it.unige.portcommand.scenario.ScriptedEventBehaviour scriptedEvents;
    /**
     * S15 (2026-10-12): the sim-minute tick publisher, held so {@link #requestPortStatus()} can
     * reach it. Volatile because that method is called from the session's and the GUI's threads,
     * while the field is written once, on this agent's thread, in {@link #onSetup()}.
     */
    private volatile SimClockTickPublisherBehaviour tickPublisher;

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
        // S5: the coordinator resolves priority overrides through the HM's escalation map, so a
        // storm-escalated vessel's re-dispatch CNP outranks queued ordinary sessions (R28/R30).
        this.cnpCoordinator = new RealCnpCoordinator(this, serviceLocator(), simClock,
                priorityOverrides::get);

        // Task 22 restore: the loader appends a HarbourMasterRestoreState (found by type).
        // Ledger BALANCES arrived via HarbourMasterInitArgs; here the in-progress day's
        // history, counters, pending customs and tracking entries are seeded, and the
        // dispatcher below re-drives what the save interrupted.
        HarbourMasterRestoreState restore = optionalArgOfType(HarbourMasterRestoreState.class);
        int startingSpawnCount = 0;
        int restoredNameOffset = -1; // sentinel: not yet drawn (fresh game / scenario opening)
        if (restore != null) {
            walletLedger.restoreHistory(restore.harbourMaster().dayIncome(),
                    restore.harbourMaster().dayExpense());
            performativeCounter.restore(restore.harbourMaster().performativeCounts());
            pendingCustomsChecks.putAll(restore.harbourMaster().pendingCustomsChecks());
            startingSpawnCount = restore.harbourMaster().walkInSpawnCount();
            restoredNameOffset = restore.harbourMaster().namePoolOffset();
            restoreTracking(restore.activeVessels());
        }
        // S8 (GOAL 3, ADR-17): the name-pool offset is rolled ONCE per game from the seeded
        // stream and then persisted — a real save carries the rolled value; a scenario-opening
        // GameState (and a pre-S8 save, whose missing field binds 0... see the DTO javadoc)
        // carries the -1 sentinel and rolls fresh here, deterministically from the world's seed.
        this.namePoolOffset = restoredNameOffset >= 0
                ? restoredNameOffset
                : Math.floorMod(randomSource.forStream("name-pool").nextInt(),
                        it.unige.portcommand.core.NamePool.size());

        addBehaviour(new AutoFlowDispatcherBehaviour(this));
        addBehaviour(new ForwardWalkInToPlayerBehaviour(this));
        this.dispatchPlayerCommand = new DispatchPlayerCommandBehaviour(this);
        addBehaviour(dispatchPlayerCommand);
        addBehaviour(new HandleEmergencyBehaviour(this));
        addBehaviour(new HandleWeatherAlertBehaviour(this));
        // S9 (P1-8): the post-award tug-decline receiver — uncounted parent-package behaviour,
        // catalogue stays 52. Permanent, because the per-cfpId bid collector deregisters at
        // award and a decline arrives after.
        addBehaviour(new it.unige.portcommand.behaviours.HandleTugDeclineBehaviour(this));
        addBehaviour(new EndOfDayDetectBehaviour(this, simClock));
        this.poissonSpawn = new PoissonSpawnBehaviour(this, simClock, randomSource, spawner,
                marketHistory, startingSpawnCount);
        addBehaviour(poissonSpawn);
        // Task 24: the single SimClockTickEvent publisher (Option B) — uncounted parent-package
        // behaviour, so the 51-catalogue is untouched.
        // S15 (2026-10-12): the same tick now also publishes the read-only PortStatusEvent (the
        // Port table's feed) whenever the snapshot from portStatus() changed. Still ONE behaviour
        // in the uncounted package — it runs on this agent's thread, the only thread allowed to
        // read the berth wait queue, so no second ticker and no change to the catalogue count.
        this.tickPublisher = new SimClockTickPublisherBehaviour(this, simClock, eventBus, this::portStatus);
        addBehaviour(tickPublisher);
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

        // S8 (GOAL 3): the comm-log paraphraser resolves vessel ids through THIS agent's live
        // tracking — "Stella Maris (WALKIN-3)" for named vessels, the bare id otherwise. Reset
        // in takeDown (the task-22 set-at-setup/clear-at-teardown discipline).
        Paraphraser.setVesselDisplay(this, vesselId -> {
            var tracking = activeVessels.get(vesselId);
            String name = tracking == null ? null : tracking.spec().vesselName();
            return name == null ? vesselId : name + " (" + vesselId + ")";
        });

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
                    dto.tonnage(), dto.cargoClass(), dto.etaArrivalSimMillis(), dto.vesselName());
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
        // S8 (GOAL 3): drop the paraphraser's reference to this world's tracking — but only if
        // WE still own the hook (compare-and-clear): JADE takedown is async, and a late
        // takedown of the outgoing world must not wipe the incoming world's freshly-set
        // resolver (adversarial finding 2).
        Paraphraser.clearVesselDisplay(this);
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
                poissonSpawn == null ? 0 : poissonSpawn.spawnCount(), namePoolOffset);
    }

    /** S8 (GOAL 3): the session's rolled name-pool offset — see the field's javadoc. */
    public int namePoolOffset() {
        return namePoolOffset;
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

    /**
     * The player's last counter price per open dialogue, keyed by conversationId (2026-08-10,
     * audit P1-9). Observable by construction — it is the player's own typed number, seen by
     * {@code DispatchPlayerCommandBehaviour} as it forwards the PROPOSE — so carrying it costs
     * no P-04 privacy. It is NOT on the wire back from the vessel: a vessel's counter carries
     * only {@code {intent, price, hours, vessel_spec}}, which is why the HarbourMaster could not
     * fill this field before and published 0.0 instead.
     */
    public ConcurrentHashMap<String, Double> negotiationPlayerOffers() {
        return negotiationPlayerOffers;
    }

    /** Zero-bid CNP retry counter per vessel (task 15's {@code CnpRetryBehaviour} seam). */
    public ConcurrentHashMap<String, AtomicInteger> cnpRetryAttempts() {
        return cnpRetryAttempts;
    }

    /** S5: the berth wait queue. HM-thread confined — call only from HM behaviours. */
    public it.unige.portcommand.harbourmaster.BerthWaitQueue berthWaitQueue() {
        return berthWaitQueue;
    }

    /** S5: queued contracted grants (vesselId → contractId); consumed by the release sweep. */
    public ConcurrentHashMap<String, String> pendingContractGrants() {
        return pendingContractGrants;
    }

    /** S5: runtime priority escalations (vesselId → emergency/medical), weather-cascade-owned. */
    public ConcurrentHashMap<String, String> priorityOverrides() {
        return priorityOverrides;
    }

    /** S5 (Goal B): the last weather reading, or null before the first alert/snapshot. */
    public WeatherReading lastWeather() {
        return lastWeather;
    }

    /** Written by {@code HandleWeatherAlertBehaviour} on every weather message it consumes. */
    public void recordWeather(WeatherReading reading) {
        this.lastWeather = reading;
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

    // --- S15 (2026-10-12): the read-only port status behind the Port table ---

    /**
     * Who holds which berth and who is waiting for one, right now — the snapshot the Port table
     * shows. The rules live in the pure {@link PortStatusSnapshot}; this method only gathers its
     * inputs from this agent's own state.
     *
     * <p><b>Call on this agent's thread only</b> (in production: the tick publisher's
     * {@code onSimTick}). The {@code BerthWaitQueue} is thread-confined by design and is read
     * here without a lock. Every other thread asks through {@link #requestPortStatus()} and
     * receives the answer as a {@link PortStatusEvent} on the bus.
     *
     * <p><b>Read-only reporting.</b> It mutates nothing, sends nothing, queries no rule and
     * decides nothing: design rule 4 is untouched because no binding message can follow from
     * it. The grant and release paths keep reading {@link #activeVessels()} themselves.
     *
     * <p>It exists because the {@code PortStateArtifact} hears of berths from the terminal, and
     * a walk-in never sends the terminal a berth REQUEST (ADR-09) — so a berth held by a walk-in
     * reads free there, and the wait queue is in no artifact or event at all.
     */
    public PortStatusEvent portStatus() {
        long now = simClock.nowSimMillis();
        List<String> queuedVesselIds = new ArrayList<>();
        for (var waiting : berthWaitQueue.inArrivalOrder()) {
            queuedVesselIds.add(waiting.vesselId());
        }
        return PortStatusSnapshot.build(SimClock.gameDayOf(now), now, activeVessels.values(),
                queuedVesselIds, this::dockedUntil);
    }

    /**
     * Asks for the port status to be published once more on the next tick, even if nothing
     * changed since the last one. <b>Safe from any thread</b> — it only raises a flag on the tick
     * publisher; the snapshot is still built and published on this agent's thread.
     *
     * <p>Needed because the status is published only when it changes and the bus has no replay:
     * a window that subscribes after the world booted ({@code --scenario}/{@code --load}), or a
     * freshly started game whose first tick beat the GUI's reset, would otherwise show an empty
     * table until the next real change. Called before setup finished it is a harmless no-op —
     * a new publisher's first tick always announces.
     */
    public void requestPortStatus() {
        SimClockTickPublisherBehaviour publisher = tickPublisher;
        if (publisher != null) {
            publisher.requestStatus();
        }
    }

    /**
     * Until when a berth holder stays docked: the vessel agent's own DOCKED-phase deadline
     * ({@code DockAndServiceBehaviour}'s service end), or empty when it is not docked yet, when
     * its agent is gone, or when this harness has no {@code AgentDirectory} (stub ITs).
     *
     * <p>Asked of the VESSEL, not of the tracking, because {@link VesselTracking.Stage} cannot
     * say "docked": a vessel that needs no tug (every ferry) emits no dock report and stays
     * IN_TRANSIT here until its {@code service_complete} (see {@code advanceStage}'s javadoc),
     * and the HarbourMaster holds no service-end time for anyone. The deadline is exact, covers
     * both channels, and survives a load — it is persisted in {@code VesselStateDTO} and
     * re-reported when the flow resumes.
     *
     * <p><b>The cross-thread read.</b> The two fields are volatile but are written as a PAIR
     * ({@code BaseVesselAgent.reportPhase}: phase first, then deadline) on the vessel's thread —
     * the same cross-thread read {@code GameStateBuilder.snapshotVessels} already makes. Two
     * guards keep a torn pair off the table:
     * <ol>
     *   <li>the deadline is read on BOTH sides of the phase; if it moved in between, a
     *       transition is in flight and the answer is "not known this tick";</li>
     *   <li>the residue — DOCKED already written, deadline still the transit's, and both reads
     *       landing inside that gap — carries a transit deadline, and a vessel docks only once
     *       that deadline has passed. The clock is therefore read AFTER the phase and a deadline
     *       that is not in the future is dropped.</li>
     * </ol>
     * A miss costs one tick: the next snapshot differs and is published.
     */
    private OptionalLong dockedUntil(VesselTracking tracking) {
        it.unige.portcommand.persistence.AgentDirectory directory = agentDirectory();
        if (directory == null || tracking.spec() == null) {
            return OptionalLong.empty();
        }
        BaseVesselAgent vessel = directory.byName(AgentRoster.vesselLocalName(tracking.spec()))
                .filter(BaseVesselAgent.class::isInstance)
                .map(BaseVesselAgent.class::cast)
                .orElse(null);
        if (vessel == null) {
            return OptionalLong.empty();
        }
        long deadlineBefore = vessel.phaseDeadlineSimMillis();
        BaseVesselAgent.FlowPhase phase = vessel.flowPhase();
        long deadlineAfter = vessel.phaseDeadlineSimMillis();
        if (phase != BaseVesselAgent.FlowPhase.DOCKED || deadlineBefore != deadlineAfter) {
            return OptionalLong.empty();
        }
        return deadlineBefore > simClock.nowSimMillis() ? OptionalLong.of(deadlineBefore) : OptionalLong.empty();
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
        // S15: ontology, protocol and content ride along for the comm log's Raw view and its
        // filters. Plain getters, no side effects on the message, nothing decided from them.
        eventBus.publish(new CommLogEvent(simClock.nowSimMillis(), sender, receivers,
                message.getPerformative(), Paraphraser.paraphrase(message, sender), message.getConversationId(),
                message.getOntology(), message.getProtocol(), message.getContent()));
    }

    /**
     * Audit catch-all union. The four permanently-registered inbound templates, PLUS the whole
     * Contract Net protocol: {@code BidCollectorBehaviour} builds its template per-cfpId at
     * runtime, so it cannot be named here, and without this exclusion the catch-all could win a
     * scheduler pass against a live bid.
     *
     * <p>The exclusion means CNP traffic is invisible to the catch-all entirely — it neither
     * reports nor consumes it. A post-close late bid is therefore NOT handled here; it is drained
     * and REJECTed by {@code BidCollectorBehaviour}'s own grace-window reaper, which is the right
     * owner because it holds the session's per-cfpId template. Only a straggler arriving after
     * that grace window still leaks.
     */
    @Override
    protected MessageTemplate handledTemplate() {
        return MessageTemplate.or(
                MessageTemplate.or(AutoFlowDispatcherBehaviour.TEMPLATE, ForwardWalkInToPlayerBehaviour.TEMPLATE),
                MessageTemplate.or(
                        MessageTemplate.or(HandleWeatherAlertBehaviour.TEMPLATE, HandleEmergencyBehaviour.TEMPLATE),
                        // S9 (P1-8): the post-award decline tag is DISTINCT from the CNP
                        // protocol — without its own arm the catch-all would swallow it.
                        MessageTemplate.or(
                                MessageTemplate.MatchProtocol(InitiateCNPBehaviour.CNP_PROTOCOL),
                                it.unige.portcommand.behaviours.HandleTugDeclineBehaviour.TEMPLATE)));
    }

}
