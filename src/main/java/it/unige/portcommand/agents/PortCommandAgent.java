package it.unige.portcommand.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.bootstrap.ServiceLocator;
import it.unige.portcommand.persistence.AgentDirectory;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.FIPAManagementVocabulary;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Common base for every Port Command agent. Owns the Directory Facilitator
 * register/deregister boilerplate and the per-agent MDC, exposing two template
 * hooks ({@link #registerServices()}, {@link #onSetup()}) so concrete agents stay
 * focused on their own concern. There is no {@code PlayerAgent} — the
 * HarbourMaster doubles as the player's proxy (CLAUDE.md rule 2).
 *
 * <p>Not a JADE-BDI framework: JADE has no BDI primitives. The belief/plan model
 * is conceptual; here it is plain fields plus {@code behaviours/} classes.
 */
public abstract class PortCommandAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final Logger log = LoggerFactory.getLogger(getClass());
    private ServiceLocator serviceLocator;
    private AgentDirectory agentDirectory;

    @Override
    protected final void setup() {
        MDC.put("agent", getLocalName());
        // DEBUG, not INFO (task 26): this fires for every agent, and at sandbox pacing a
        // walk-in spawns roughly every 24 s. Agent lifecycle is developer information; the
        // game-level "a vessel arrived" line below it is the operator information.
        log.debug("Starting agent {}", getLocalName());
        // Per-agent locator, bound to this agent's thread. getContainerController()
        // is a JADE instance handle (verified via javap), not a static registry —
        // honours the constructor-arg/no-static-DI rule (ADR-02).
        this.serviceLocator = new ServiceLocator(getContainerController(), this);
        // Task 22: if the spawner threaded an AgentDirectory through the args channel
        // (position-independent — scanned by type), self-register so the save snapshot can
        // reach this agent's beliefs (jade.wrapper deliberately hides the Agent object).
        this.agentDirectory = optionalArgOfType(AgentDirectory.class);
        if (agentDirectory != null) {
            agentDirectory.register(this);
        }
        registerServices();
        onSetup();
    }

    @Override
    protected final void takeDown() {
        if (agentDirectory != null) {
            agentDirectory.unregister(this);
        }
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            log.warn("DF deregister failed for {}", getLocalName(), e);
        }
        onTakeDown();
        MDC.remove("agent");
    }

    /** Subclass registers its DF service(s) via {@link #registerDfService}. */
    protected abstract void registerServices();

    /** Subclass-specific init after DF registration (parse args, add behaviours). */
    protected abstract void onSetup();

    /** Optional teardown hook; default no-op. */
    protected void onTakeDown() {
    }

    /**
     * Per-attempt bound on the DF register round trip — a healthy DF answers in
     * milliseconds; the 2026-07-18 full-suite wedge sat 30+ s. Attempts × timeout +
     * spacings = 3×2500 + 2×1200 = 9.9 s, deliberately INSIDE GameSession's 10 s
     * {@code awaitHarbourMasterReady} bound, so even the HarbourMaster's own
     * registration either self-heals (a one-blip stall retries at ~3.7 s) or fails
     * loudly through the session layer — never a silent hang.
     */
    private static final long DF_REGISTER_TIMEOUT_MS = 2_500L;
    private static final int DF_REGISTER_ATTEMPTS = 3;
    /** Mirrors the DF-registration-send retry invariant's spacing (INVARIANTS.md). */
    private static final long DF_REGISTER_RETRY_SPACING_MS = 1_200L;

    /**
     * Registers one DF service for this agent — BOUNDED (2026-07-18): the stock
     * {@code DFService.register} blocks with no timeout, and during a load's
     * teardown→rebuild a respawning agent could wedge forever in setup if the rebuilt
     * platform's DF stalled under machine load (observed once in a full-suite run —
     * BUILD_LOG "Checkpoint-#6 fix round", flake log). This composes the SAME
     * primitives {@code DFService.register} does internally
     * ({@code createRequestMessage} + {@code doFipaRequestClient}), but through the
     * TIMED {@code doFipaRequestClient} overload (null reply == timeout, JADE
     * contract), retrying up to {@link #DF_REGISTER_ATTEMPTS} times.
     *
     * <p>A timed-out attempt may still have REACHED the DF (only the reply was late),
     * so a retry can come back {@code already-registered} — that IS success, not a
     * rejection, and is treated as such. Any other REFUSE/FAILURE stays fail-fast,
     * exactly the pre-existing semantics. Exhausting every attempt throws — a loud,
     * bounded failure (the session layer lands in MENU) instead of the hang.
     *
     * <p>The DF request rides JADE's own FIPA-Agent-Management envelope (built by
     * {@code DFService.createRequestMessage}) — deliberately NOT
     * {@code MessageFactory.create}: rule 5 governs the game's {@code port_command_v1}
     * protocol messages, and the stock {@code DFService.register} has always built
     * this infra message internally; stamping the app envelope onto it would break
     * the DF's SL codec.
     */
    protected final void registerDfService(String serviceType, String serviceName) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        sd.setName(serviceName);
        dfd.addServices(sd);
        for (int attempt = 1; attempt <= DF_REGISTER_ATTEMPTS; attempt++) {
            try {
                ACLMessage request = DFService.createRequestMessage(this, getDefaultDF(),
                        FIPAManagementVocabulary.REGISTER, dfd, null);
                ACLMessage reply = FIPAService.doFipaRequestClient(this, request, DF_REGISTER_TIMEOUT_MS);
                if (reply != null) {
                    log.debug("DF registered: type='{}' name='{}'", serviceType, serviceName);
                    return;
                }
                log.warn("DF register timed out ({} ms) for {} (service '{}', attempt {}/{})",
                        DF_REGISTER_TIMEOUT_MS, getLocalName(), serviceType, attempt, DF_REGISTER_ATTEMPTS);
            } catch (FIPAException e) {
                if (attempt > 1 && String.valueOf(e.getMessage()).contains("already-registered")) {
                    // A previous timed-out attempt actually landed; the DF knows us. Success.
                    log.debug("DF registered: type='{}' name='{}' (prior attempt had landed)",
                            serviceType, serviceName);
                    return;
                }
                throw new IllegalStateException(
                        "DF registration failed for " + getLocalName() + " (service " + serviceType + ")", e);
            }
            if (attempt < DF_REGISTER_ATTEMPTS) {
                doWait(DF_REGISTER_RETRY_SPACING_MS);
            }
        }
        throw new IllegalStateException("DF registration timed out for " + getLocalName()
                + " (service " + serviceType + ") after " + DF_REGISTER_ATTEMPTS + " attempts of "
                + DF_REGISTER_TIMEOUT_MS + " ms — the platform DF is not answering");
    }

    /** AID lookup helper for subclasses; never call {@code DFService.search} directly. */
    protected final ServiceLocator getServiceLocator() {
        return serviceLocator;
    }

    /**
     * Reads {@code getArguments()[0]} as {@code type}: a direct cast when the
     * spawner passed a real record, else a Jackson conversion (future JSON args).
     * Returns {@code null} when no args were supplied — stub agents tolerate this.
     */
    protected final <T> T initArg(Class<T> type) {
        Object[] args = getArguments();
        if (args == null || args.length == 0 || args[0] == null) {
            return null;
        }
        Object first = args[0];
        return type.isInstance(first) ? type.cast(first) : MAPPER.convertValue(first, type);
    }

    /**
     * The first spawn argument that IS an instance of {@code type}, or {@code null} — a
     * position-independent read for OPTIONAL args (task 22's restore DTOs and the
     * {@link AgentDirectory}), so appending them never disturbs the index-based required
     * args or the tests that pass shorter arrays.
     */
    protected final <T> T optionalArgOfType(Class<T> type) {
        Object[] args = getArguments();
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (type.isInstance(arg)) {
                return type.cast(arg);
            }
        }
        return null;
    }

    /** Shared mapper for the agents' {@code Mementoable} JsonNode round-trips (task 22). */
    protected static ObjectMapper persistenceMapper() {
        return MAPPER;
    }

    /** The session's live-agent registry, or {@code null} when not threaded through the args
     * channel. The HarbourMaster forwards it to the walk-in spawner so dynamically spawned
     * vessels register too (task 22). */
    protected final AgentDirectory agentDirectory() {
        return agentDirectory;
    }
}
