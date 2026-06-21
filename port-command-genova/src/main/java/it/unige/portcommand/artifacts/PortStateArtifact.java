package it.unige.portcommand.artifacts;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.ontology.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe shared port state: per-berth occupancy and per-tug status, plus a
 * publish/subscribe channel for deltas. One instance is created by
 * {@code JadeBootstrap} and constructor-injected (via the {@code Object[]} args
 * channel) into the terminal and tug agents — never a global static. The GUI map
 * (task 18) reads snapshots directly; task 08's escort behaviour subscribes.
 */
public final class PortStateArtifact {

    private static final Logger log = LoggerFactory.getLogger(PortStateArtifact.class);

    private final Map<String, BerthOccupancy> berthOccupancy = new ConcurrentHashMap<>();
    private final Map<String, TugStatus> tugStatuses = new ConcurrentHashMap<>();
    private final List<Consumer<PortStateUpdate>> subscribers = new CopyOnWriteArrayList<>();

    /** Atomically records a berth delta, then notifies subscribers. */
    public void update(BerthOccupancyUpdate delta) {
        berthOccupancy.put(delta.berthId(), delta.occupancy());
        emit(new PortStateUpdate.BerthDelta(delta.berthId(), delta.occupancy()));
    }

    /** Atomically records a tug's position+status, then notifies subscribers. Task 08 calls this each transit tick. */
    public void updateTug(String tugId, Position position, TugStatus status) {
        tugStatuses.put(tugId, status);
        emit(new PortStateUpdate.TugDelta(tugId, position, status));
    }

    public Optional<BerthOccupancy> getBerth(String berthId) {
        return Optional.ofNullable(berthOccupancy.get(berthId));
    }

    /** Immutable snapshot of all berth occupancy (GUI map reads this each animation tick). */
    public Map<String, BerthOccupancy> berthSnapshot() {
        return Map.copyOf(berthOccupancy);
    }

    /** Immutable snapshot of all tug statuses. */
    public Map<String, TugStatus> tugSnapshot() {
        return Map.copyOf(tugStatuses);
    }

    public void subscribe(Consumer<PortStateUpdate> subscriber) {
        subscribers.add(subscriber);
    }

    private void emit(PortStateUpdate update) {
        for (Consumer<PortStateUpdate> subscriber : subscribers) {
            try {
                subscriber.accept(update);
            } catch (RuntimeException e) {
                log.warn("PortStateArtifact subscriber threw on {}", update.getClass().getSimpleName(), e);
            }
        }
    }
}
