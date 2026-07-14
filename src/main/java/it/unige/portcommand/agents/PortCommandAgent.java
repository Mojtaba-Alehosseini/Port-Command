package it.unige.portcommand.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unige.portcommand.bootstrap.ServiceLocator;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
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

    @Override
    protected final void setup() {
        MDC.put("agent", getLocalName());
        log.info("Starting agent {}", getLocalName());
        // Per-agent locator, bound to this agent's thread. getContainerController()
        // is a JADE instance handle (verified via javap), not a static registry —
        // honours the constructor-arg/no-static-DI rule (ADR-02).
        this.serviceLocator = new ServiceLocator(getContainerController(), this);
        registerServices();
        onSetup();
    }

    @Override
    protected final void takeDown() {
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

    /** Registers one DF service for this agent. Fails fast if the DF rejects it. */
    protected final void registerDfService(String serviceType, String serviceName) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        sd.setName(serviceName);
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            log.info("DF registered: type='{}' name='{}'", serviceType, serviceName);
        } catch (FIPAException e) {
            throw new IllegalStateException(
                    "DF registration failed for " + getLocalName() + " (service " + serviceType + ")", e);
        }
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
}
