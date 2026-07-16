package it.unige.portcommand.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import it.unige.portcommand.artifacts.MarketHistoryArtifact;
import it.unige.portcommand.behaviours.coordination.AutoFlowDispatcherBehaviour;
import it.unige.portcommand.behaviours.coordination.DispatchPlayerCommandBehaviour;
import it.unige.portcommand.behaviours.coordination.EndOfDayDetectBehaviour;
import it.unige.portcommand.behaviours.coordination.ForwardWalkInToPlayerBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleEmergencyBehaviour;
import it.unige.portcommand.behaviours.coordination.HandleWeatherAlertBehaviour;
import it.unige.portcommand.behaviours.coordination.PoissonSpawnBehaviour;
import it.unige.portcommand.bootstrap.JadeAgentSpawner;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.commlog.Paraphraser;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.harbourmaster.CnpCoordinator;
import it.unige.portcommand.harbourmaster.RealCnpCoordinator;
import it.unige.portcommand.harbourmaster.ReputationLedger;
import it.unige.portcommand.harbourmaster.VesselTracking;
import it.unige.portcommand.harbourmaster.WalletLedger;
import it.unige.portcommand.ontology.ServiceContract;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.RandomSource;
import it.unige.portcommand.util.SimClock;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

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
public final class HarbourMasterAgent extends PortCommandAgent {

    private final ConcurrentHashMap<String, VesselTracking> activeVessels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pendingCustomsChecks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> negotiationBerths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> negotiationRounds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> cnpRetryAttempts = new ConcurrentHashMap<>();

    private WalletLedger walletLedger;
    private ReputationLedger reputationLedger;
    private SimClock simClock;
    private RandomSource randomSource;
    private EventBus eventBus;
    private Map<String, ServiceContract> contracts;
    private CnpCoordinator cnpCoordinator;
    private volatile AID customsAid;

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
        this.walletLedger = new WalletLedger(init.startingWallet());
        this.reputationLedger = new ReputationLedger(init.startingReputation());
        this.simClock = argAt(1, SimClock.class);
        this.randomSource = argAt(2, RandomSource.class);
        this.eventBus = argAt(3, EventBus.class);
        this.contracts = contractsArg();
        JadeAgentSpawner spawner = argAt(5, JadeAgentSpawner.class);
        MarketHistoryArtifact marketHistory = argAt(6, MarketHistoryArtifact.class);
        this.cnpCoordinator = new RealCnpCoordinator(this, serviceLocator(), simClock);

        addBehaviour(new AutoFlowDispatcherBehaviour(this));
        addBehaviour(new ForwardWalkInToPlayerBehaviour(this));
        addBehaviour(new DispatchPlayerCommandBehaviour(this));
        addBehaviour(new HandleEmergencyBehaviour(this));
        addBehaviour(new HandleWeatherAlertBehaviour(this));
        addBehaviour(new EndOfDayDetectBehaviour(this, simClock));
        addBehaviour(new PoissonSpawnBehaviour(this, simClock, randomSource, spawner, marketHistory));

        completeOptionalSelfFuture();
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

    public Map<String, ServiceContract> contracts() {
        return contracts;
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

    public ReputationLedger reputationLedger() {
        return reputationLedger;
    }

    /**
     * Public wrapper — {@code PortCommandAgent.getServiceLocator()} is protected,
     * and the coordination behaviours that need it live in a different package
     * (same pattern as {@code BaseVesselAgent.serviceLocator()}).
     */
    public ServiceLocator serviceLocator() {
        return getServiceLocator();
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
                message.getPerformative(), Paraphraser.paraphrase(message), message.getConversationId()));
    }
}
