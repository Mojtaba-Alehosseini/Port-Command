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
    /**
     * S16 (ADR-20): what is known about ships waiting to move - tugs on station, hold rules,
     * departure requests, inspections, closed berths. See {@link MovementBook}.
     */
    private final it.unige.portcommand.harbourmaster.MovementBook movement =
            new it.unige.portcommand.harbourmaster.MovementBook();
    /**
     * S16 (ADR-20): whether this world has a customs office. Every production boot does; a
     * HarbourMaster-only test harness does not, and must not make every ship wait for a
     * clearance nobody will send.
     */
    private boolean customsOffice;

    /** The four fields every weather message carries, as one immutable cell. */
    public record WeatherReading(double windKnots, String visibility, double swell, String state) {
    }

    private WalletLedger walletLedger;
    private ReputationLedger reputationLedger;
    private LedgerCoordinator ledgerCoordinator;
    private PerformativeCounter performativeCounter;
    private DayRolloverCoordinator dayRolloverCoordinator;
    /** S17b (ADR-22): what a day of this session is measured against and owes. */
    private it.unige.portcommand.harbourmaster.financial.SessionTerms sessionTerms;
    /** S17b (ADR-22): the figures behind the day's score. */
    private volatile it.unige.portcommand.harbourmaster.financial.ScoreKeeper scoreKeeper;
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
    /**
     * S17b (T1, PT8): walk-ins that have been spawned and have not hailed yet: vessel id -> the
     * sim time of the spawn. A walk-in is not in {@link #activeVessels} until its opening offer
     * arrives, a few hundred real milliseconds after the spawn; without this, two arrivals asked
     * for in that gap (two scripted walk-ins due together, two quick presses of "Call next ship")
     * would each see a free slot. An entry goes when the ship hails or is turned away, and after
     * {@link #WALK_IN_EN_ROUTE_MAX_REAL_MILLIS} at the latest, so a ship that never hails cannot
     * hold a slot for good.
     *
     * <p><b>The limit is REAL time, kept on the conversation clock</b> (adversarial review): what
     * it bounds - spawn, DF registration, DF search, the opening PROPOSE - is wall latency, the
     * same at every game pace. A limit in game time shrank with the pace: five game minutes are
     * twelve real seconds at a 60-minute day and a quarter of a second at a 5-minute day at x4.
     * {@code ConversationClock} already counts real milliseconds for the walk-ins' patience
     * (fed through {@code SimClock.advance}, unscaled, frozen by a pause), so no second
     * wall-clock reader is needed.
     */
    private final ConcurrentHashMap<String, it.unige.portcommand.util.ConversationClock.Conversation>
            walkInsEnRoute = new ConcurrentHashMap<>();
    private static final long WALK_IN_EN_ROUTE_MAX_REAL_MILLIS = 15_000L;
    /** S17b (PT8): the "Call next ship" listener; cancelled in {@link #onTakeDown()}. */
    private it.unige.portcommand.util.Subscription<it.unige.portcommand.gui.events.CallNextShipEvent>
            callNextSubscription;
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
        // S17b (ADR-22): the session's terms come from the scenario this world was booted with -
        // its name, its own target, the hour it opens at - and are free play's when there is none.
        // Derived on every boot (a load passes the same runtime), never saved.
        it.unige.portcommand.scenario.ScenarioRuntime bootRuntime =
                optionalArgOfType(it.unige.portcommand.scenario.ScenarioRuntime.class);
        it.unige.portcommand.scenario.Scenario bootScenario =
                bootRuntime == null ? null : bootRuntime.scenario();
        this.sessionTerms = bootScenario == null
                ? it.unige.portcommand.harbourmaster.financial.SessionTerms.freePlay()
                : it.unige.portcommand.harbourmaster.financial.SessionTerms.scenario(
                        bootScenario.displayName(), bootScenario.targetDailyRevenue(),
                        bootScenario.initialState().startSimMillis());
        // Plain bus subscribers, not JADE behaviours — the catalogue stays locked at 52 (ADR-01).
        this.scoreKeeper = new it.unige.portcommand.harbourmaster.financial.ScoreKeeper(
                eventBus, simClock, walletLedger, reputationLedger, sessionTerms);
        this.ledgerCoordinator = new LedgerCoordinator(eventBus, walletLedger, reputationLedger,
                scoreKeeper, this::vesselDisplayName);
        this.performativeCounter = new PerformativeCounter(eventBus);
        // Task 24: the EOD owner. Constructed here (LedgerCoordinator precedent) because every
        // collaborator is agent-owned; it subscribes to EndOfDayDetectBehaviour's EndOfDayEvent.
        this.dayRolloverCoordinator = new DayRolloverCoordinator(eventBus, simClock, walletLedger,
                reputationLedger, performativeCounter, sessionTerms, scoreKeeper);
        // Task 23: mutable since the scenario engine registers scenario-local contracts and
        // the daily stream's day-qualified clones at runtime (ConcurrentHashMap — behaviours
        // read it on this thread, but the map is advertised shared state like the trackers).
        this.contracts = new ConcurrentHashMap<>(contractsArg());
        JadeAgentSpawner spawner = argAt(5, JadeAgentSpawner.class);
        MarketHistoryArtifact marketHistory = argAt(6, MarketHistoryArtifact.class);
        // S5: the coordinator resolves priority overrides through the HM's escalation map, so a
        // storm-escalated vessel's re-dispatch CNP outranks queued ordinary sessions (R28/R30).
        this.cnpCoordinator = new RealCnpCoordinator(this, serviceLocator(), simClock,
                id -> it.unige.portcommand.behaviours.MovementControl.priorityOverrideFor(this, id));
        // S16 (B7, ADR-20): the movement gate has a reading from the first instant. Before S16
        // the reading was null until the first weather message - up to fifteen game minutes after
        // every boot, and every load - and null meant "allow": a game loaded in mid-storm sent
        // ships into it. The roster passes the weather the world opens on (the saved snapshot on
        // a load); a harness that passes none starts on calm weather, stated in its init args.
        this.customsOffice = init.customsOffice();
        if (init.openingWeather() != null) {
            WeatherSnapshot opening = init.openingWeather();
            this.lastWeather = new WeatherReading(opening.wind(), opening.visibility(),
                    opening.swell(), opening.state());
        }

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
            // S17b: after the history, because the day's money is read back from it.
            scoreKeeper.restore(restore.harbourMaster().score());
            performativeCounter.restore(restore.harbourMaster().performativeCounts());
            pendingCustomsChecks.putAll(restore.harbourMaster().pendingCustomsChecks());
            startingSpawnCount = restore.harbourMaster().walkInSpawnCount();
            restoredNameOffset = restore.harbourMaster().namePoolOffset();
            restoreTracking(restore.activeVessels());
            movement.restoreClosedBerths(restore.harbourMaster().closedBerths());
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
        // S17b (PT8): "Call next ship". The press arrives on a bus thread; the spawn - like every
        // spawn - happens on this agent's thread, as a queued one-shot step.
        this.callNextSubscription = eventBus.subscribe(
                it.unige.portcommand.gui.events.CallNextShipEvent.class,
                press -> addBehaviour(new jade.core.behaviours.OneShotBehaviour(this) {
                    @Override
                    public void action() {
                        callNextShip();
                    }
                }), it.unige.portcommand.util.DeliveryMode.ASYNC);
        // Task 24: the single SimClockTickEvent publisher (Option B) — uncounted parent-package
        // behaviour, so the catalogue (52 since S8) is untouched.
        // S15 (2026-10-12): the same tick now also publishes the read-only PortStatusEvent (the
        // Port table's feed) whenever the snapshot from portStatus() changed. Still ONE behaviour
        // in the uncounted package — it runs on this agent's thread, the only thread allowed to
        // read the berth wait queue, so no second ticker and no change to the catalogue count.
        // S16: the same once-a-sim-minute pass, on this agent's thread, first reopens any flooded
        // berth whose closure has run out (and re-evaluates the ships that waited for it).
        this.tickPublisher = new SimClockTickPublisherBehaviour(this, simClock, eventBus, () -> {
            it.unige.portcommand.behaviours.MovementControl.reopenDueBerths(this);
            return portStatus();
        });
        addBehaviour(tickPublisher);
        if (restore != null) {
            addBehaviour(new RestoreDispatchBehaviour(this));
        }

        // Task 23: the scenario engine (scenario-package behaviour — catalogue stays 52,
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
        if (scoreKeeper != null) {
            scoreKeeper.close();
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
        if (callNextSubscription != null) {
            callNextSubscription.cancel();
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
                poissonSpawn == null ? 0 : poissonSpawn.spawnCount(), namePoolOffset,
                movement.closedBerths(), scoreKeeper == null ? null : scoreKeeper.snapshot());
    }

    /** S17b (PT8): brings the next walk-in in now, or says in the strip why not. Agent thread. */
    private void callNextShip() {
        String refusal = poissonSpawn.callNext();
        if (refusal != null) {
            eventBus.publish(new it.unige.portcommand.gui.events.NotificationEvent(refusal,
                    it.unige.portcommand.gui.events.NotificationEvent.Severity.INFO,
                    simClock.nowSimMillis()));
        }
    }

    /** S17b: a walk-in was spawned and will hail in a moment (see {@link #walkInsEnRoute}). */
    public void walkInSpawned(String vesselId) {
        walkInsEnRoute.put(vesselId, simClock.conversationClock()
                .open("en-route-" + vesselId, WALK_IN_EN_ROUTE_MAX_REAL_MILLIS));
    }

    /** S17b: the walk-in's opening offer has arrived - it is tracked from here on, or it was
     * turned away and is no longer coming. */
    public void walkInHailed(String vesselId) {
        var timer = walkInsEnRoute.remove(vesselId);
        if (timer != null) {
            timer.close();
        }
    }

    /** S17b: walk-ins the player is talking to, or will be in a moment - the "at most two" count. */
    public int negotiatingWalkIns() {
        long tracked = activeVessels.values().stream()
                .filter(v -> v.channel() == VesselTracking.Channel.WALK_IN
                        && v.stage() == VesselTracking.Stage.NEGOTIATING)
                .count();
        return (int) tracked + walkInsStillEnRoute();
    }

    /** S17b: walk-ins in port at any stage, those about to hail included - the "at most four". */
    public int walkInsInPort() {
        long tracked = activeVessels.values().stream()
                .filter(v -> v.channel() == VesselTracking.Channel.WALK_IN)
                .count();
        return (int) tracked + walkInsStillEnRoute();
    }

    private int walkInsStillEnRoute() {
        walkInsEnRoute.entrySet().removeIf(e -> {
            // Tracked by now; or its time is up; or the clock was restored under it (no view).
            boolean over = activeVessels.containsKey(e.getKey())
                    || e.getValue().view().map(it.unige.portcommand.util.ConversationClock.View::expired)
                            .orElse(true);
            if (over) {
                e.getValue().close();
            }
            return over;
        });
        return walkInsEnRoute.size();
    }

    /** S17b: a ship's name as the player knows it - its display name, or its id if it has none
     * or is no longer tracked. Safe from any thread (one concurrent-map read). */
    public String vesselDisplayName(String vesselId) {
        VesselTracking tracking = vesselId == null ? null : activeVessels.get(vesselId);
        String name = tracking == null || tracking.spec() == null ? null : tracking.spec().vesselName();
        return name == null || name.isBlank() ? String.valueOf(vesselId) : name;
    }

    /** S17b (ADR-22): the session's target, hours and scenario day. */
    public it.unige.portcommand.harbourmaster.financial.SessionTerms sessionTerms() {
        return sessionTerms;
    }

    /** S17b (ADR-22): the figures behind the day's score. */
    public it.unige.portcommand.harbourmaster.financial.ScoreKeeper scoreKeeper() {
        return scoreKeeper;
    }

    /** S17b: announces the day's score once more, for a window that has just opened or a game
     * that has just loaded. Safe from any thread; a no-op while this agent is still setting up. */
    public void publishScore() {
        it.unige.portcommand.harbourmaster.financial.ScoreKeeper keeper = scoreKeeper;
        if (keeper != null) {
            keeper.publishNow();
        }
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
        scoreKeeper.restore(dto.score());
        performativeCounter.restore(dto.performativeCounts());
        pendingCustomsChecks.putAll(dto.pendingCustomsChecks());
        movement.restoreClosedBerths(dto.closedBerths());
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

    /** S5 (Goal B): the last weather reading. Since S16 never null in a production world (see onSetup). */
    public WeatherReading lastWeather() {
        return lastWeather;
    }

    /** S16 (ADR-20): the waiting-ship notebook. HarbourMaster thread writes; see {@link MovementBook}. */
    public it.unige.portcommand.harbourmaster.MovementBook movement() {
        return movement;
    }

    /** S16 (ADR-20): whether a customs office exists in this world to clear arriving ships. */
    public boolean customsOffice() {
        return customsOffice;
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
                queuedVesselIds, this::dockedUntil,
                tracking -> it.unige.portcommand.behaviours.MovementControl.waitOf(this, tracking),
                berthId -> movement.berthClosed(berthId, now));
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
