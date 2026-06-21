package it.unige.portcommand.agents;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import it.unige.portcommand.agents.BerthOccupancy.Status;

/**
 * Mutable berth/crane bookkeeping for one {@code TerminalAgent}. Berth ownership
 * is fixed at construction. Each public method is {@code synchronized}: JADE
 * serialises an agent's behaviours, but a ticker callback may run on the JADE
 * scheduler thread, so we guard the shared map.
 */
public final class TerminalState {

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    /** Outcome of {@link #requestBerth}: either a confirmation (crane + free-at) or a refusal reason. */
    public record Result(boolean confirmed, int craneId, long expectedFreeAtSim, String reason) {
        public static Result confirmed(int craneId, long expectedFreeAtSim) {
            return new Result(true, craneId, expectedFreeAtSim, null);
        }

        public static Result refused(String reason) {
            return new Result(false, 0, 0L, reason);
        }
    }

    private final List<String> managedBerths;
    private final int cranesTotal;
    private final Map<String, BerthOccupancy> occupancy = new HashMap<>();

    public TerminalState(List<String> managedBerths, int cranesTotal) {
        this.managedBerths = List.copyOf(managedBerths);
        this.cranesTotal = cranesTotal;
        for (String berthId : this.managedBerths) {
            occupancy.put(berthId, BerthOccupancy.free(berthId));
        }
    }

    /** {@code true} iff this terminal owns {@code berthId}. */
    public boolean manages(String berthId) {
        return managedBerths.contains(berthId);
    }

    /**
     * Provisionally books {@code berthId}: refuses {@code berth_busy} if occupied,
     * {@code no_crane} if every crane is in use, else assigns the lowest free crane
     * and returns a confirmation with the computed {@code expectedFreeAtSim}.
     */
    public synchronized Result requestBerth(String berthId, String vesselId, long etaSim, int durationHours) {
        BerthOccupancy current = occupancy.get(berthId);
        if (current != null && current.status() != Status.FREE) {
            return Result.refused("berth_busy");
        }
        int crane = nextFreeCrane();
        if (crane == 0) {
            return Result.refused("no_crane");
        }
        long expectedFreeAtSim = etaSim + (long) durationHours * MILLIS_PER_HOUR;
        occupancy.put(berthId,
                new BerthOccupancy(berthId, vesselId, etaSim, expectedFreeAtSim, crane, Status.PROVISIONAL));
        return Result.confirmed(crane, expectedFreeAtSim);
    }

    /** PROVISIONAL → DOCKED when the vessel arrives; returns the new occupancy if it transitioned. */
    public synchronized Optional<BerthOccupancy> confirmDocking(String berthId) {
        BerthOccupancy o = occupancy.get(berthId);
        if (o != null && o.status() == Status.PROVISIONAL) {
            BerthOccupancy docked = o.withStatus(Status.DOCKED);
            occupancy.put(berthId, docked);
            return Optional.of(docked);
        }
        return Optional.empty();
    }

    /** Any non-free state → FREE; returns the freed occupancy if it transitioned. */
    public synchronized Optional<BerthOccupancy> releaseBerth(String berthId) {
        BerthOccupancy o = occupancy.get(berthId);
        if (o != null && o.status() != Status.FREE) {
            BerthOccupancy freed = BerthOccupancy.free(berthId);
            occupancy.put(berthId, freed);
            return Optional.of(freed);
        }
        return Optional.empty();
    }

    /** Clears a still-PROVISIONAL booking for {@code vesselId} (CANCEL before docking). */
    public synchronized Optional<BerthOccupancy> cancelProvisional(String vesselId) {
        for (BerthOccupancy o : occupancy.values()) {
            if (vesselId.equals(o.vesselId()) && o.status() == Status.PROVISIONAL) {
                BerthOccupancy freed = BerthOccupancy.free(o.berthId());
                occupancy.put(o.berthId(), freed);
                return Optional.of(freed);
            }
        }
        return Optional.empty();
    }

    public synchronized int cranesInUse() {
        return (int) occupancy.values().stream().filter(o -> o.status() != Status.FREE).count();
    }

    public synchronized int cranesFree() {
        return cranesTotal - cranesInUse();
    }

    public synchronized Optional<BerthOccupancy> berth(String berthId) {
        return Optional.ofNullable(occupancy.get(berthId));
    }

    /** Snapshot copy of every managed berth's occupancy (safe to iterate off-lock). */
    public synchronized List<BerthOccupancy> occupancies() {
        return List.copyOf(occupancy.values());
    }

    private int nextFreeCrane() {
        Set<Integer> used = new HashSet<>();
        for (BerthOccupancy o : occupancy.values()) {
            if (o.status() != Status.FREE) {
                used.add(o.craneId());
            }
        }
        for (int crane = 1; crane <= cranesTotal; crane++) {
            if (!used.contains(crane)) {
                return crane;
            }
        }
        return 0;
    }
}
