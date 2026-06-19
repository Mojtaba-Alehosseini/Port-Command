package it.unige.portcommand.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jade.core.AID;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.wrapper.AgentContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper over the JADE Directory Facilitator {@code search}, with a 1-second
 * TTL cache to limit DF round-trips. No agent calls {@code DFService.search}
 * directly — they go through here (task 05 hard rule).
 *
 * <p>{@code DFService.search} requires an {@link Agent}, so a locator is bound to
 * one caller agent and its lookups MUST run on that agent's own thread.
 */
public final class ServiceLocator {

    private static final Logger log = LoggerFactory.getLogger(ServiceLocator.class);
    private static final long TTL_MILLIS = 1_000L;

    @SuppressWarnings("unused") // kept per the task-03 constructor contract; reserved for future use
    private final AgentContainer container;
    private final Agent callerAgent;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ServiceLocator(AgentContainer container, Agent callerAgent) {
        this.container = Objects.requireNonNull(container, "container");
        this.callerAgent = Objects.requireNonNull(callerAgent, "callerAgent");
    }

    /** All AIDs advertising {@code serviceType}, or an empty list. */
    public List<AID> findAll(String serviceType) {
        return List.copyOf(lookup(serviceType));
    }

    /** The single AID for {@code serviceType}; empty if none. Logs WARN and returns the first if &gt;1. */
    public Optional<AID> findUnique(String serviceType) {
        List<AID> all = lookup(serviceType);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        if (all.size() > 1) {
            log.warn("findUnique({}) found {} providers; returning the first", serviceType, all.size());
        }
        return Optional.of(all.get(0));
    }

    private List<AID> lookup(String serviceType) {
        Objects.requireNonNull(serviceType, "serviceType");
        // Wall clock here is fine: it gates a DF-chatter cache TTL, not game logic.
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(serviceType);
        if (cached != null && (now - cached.timestamp()) < TTL_MILLIS) {
            return cached.aids();
        }
        List<AID> result = search(serviceType);
        cache.put(serviceType, new CacheEntry(result, now));
        return result;
    }

    private List<AID> search(String serviceType) {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        template.addServices(sd);
        try {
            DFAgentDescription[] results = DFService.search(callerAgent, template);
            List<AID> aids = new ArrayList<>(results.length);
            for (DFAgentDescription dfd : results) {
                aids.add(dfd.getName());
            }
            return aids;
        } catch (FIPAException e) {
            log.warn("DF search for service '{}' failed", serviceType, e);
            return List.of();
        }
    }

    private record CacheEntry(List<AID> aids, long timestamp) {
    }
}
