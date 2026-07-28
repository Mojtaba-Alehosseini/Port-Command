package it.unige.portcommand.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import jade.core.Agent;

/**
 * Live registry of this session's agent OBJECTS, keyed by local name (task 22). JADE's
 * {@code jade.wrapper} API deliberately hides the {@link Agent} behind
 * {@code AgentController}, so a snapshot walk cannot reach an agent's beliefs through the
 * container — the established workaround is the self-reference seam
 * ({@code HarbourMasterAgent}'s optional args future); this class is that seam generalised:
 * each {@code PortCommandAgent} finds the directory among its spawn args, registers itself
 * at setup, and unregisters at takedown.
 *
 * <p>NOT a static registry (ADR-02): one instance is constructed per session by
 * {@code GameSession}/tests and injected through the args channel like every other shared
 * service. It intentionally survives the save/load teardown-rebuild — dying agents remove
 * themselves, respawned agents re-register — so the snapshot walk after a reload sees
 * exactly the live world.
 */
public final class AgentDirectory {

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    /** Called by {@code PortCommandAgent.setup()} when it finds this directory in its args. */
    public void register(Agent agent) {
        register(agent.getLocalName(), agent);
    }

    /** Explicit-name variant ({@code Agent.getLocalName()} is final and container-bound —
     * unit tests register unbound agents under a chosen name through this form). */
    public void register(String localName, Agent agent) {
        agents.put(localName, agent);
    }

    /** Called by {@code PortCommandAgent.takeDown()}; removes only this exact instance. */
    public void unregister(Agent agent) {
        unregister(agent.getLocalName(), agent);
    }

    /** Explicit-name variant, mirroring {@link #register(String, Agent)}. */
    public void unregister(String localName, Agent agent) {
        agents.remove(localName, agent);
    }

    public Optional<Agent> byName(String localName) {
        return Optional.ofNullable(agents.get(localName));
    }

    /** Live agents of {@code type}, sorted by local name — the serialized-map determinism rule. */
    public <T> List<T> byType(Class<T> type) {
        return agents.entrySet().stream()
                .filter(e -> type.isInstance(e.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> type.cast(e.getValue()))
                .toList();
    }

    /** Live agents matching {@code filter}, sorted by local name. */
    public List<Agent> matching(Predicate<Agent> filter) {
        return agents.entrySet().stream()
                .filter(e -> filter.test(e.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    public int size() {
        return agents.size();
    }
}
