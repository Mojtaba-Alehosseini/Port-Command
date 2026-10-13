package it.unige.portcommand.harbourmaster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;

import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.PortStatusEvent.BerthRow;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;

/**
 * Turns the HarbourMaster's own tracking into the Port table's snapshot (S15, 2026-10-12): who
 * holds which berth, and who is waiting for one.
 *
 * <p><b>Why the HarbourMaster's tracking and not the {@code PortStateArtifact}.</b> The artifact is
 * written only on the terminal's side of the CONTRACTED flow; a walk-in never sends the terminal a
 * berth REQUEST (ADR-09), so a berth held by a walk-in reads FREE there, and the wait queue appears
 * in no artifact or event at all. The one place that knows both is {@code activeVessels} plus the
 * {@link BerthWaitQueue} — so that is what this reads.
 *
 * <p><b>Pure on purpose.</b> No agent, no clock, no bus: every input is a parameter, so the rules
 * below are unit-tested without a container ({@code PortStatusSnapshotTest}). The one thing the
 * tracking cannot answer — until when a docked vessel stays — is asked through
 * {@code dockedUntil}, which the agent-side glue ({@code HarbourMasterAgent.portStatus}) fills
 * from the vessel agent's own flow phase. This package imports nothing from {@code agents/}, and
 * that split is what keeps it so.
 *
 * <p><b>It reports, it never decides.</b> Nothing binding is derived from the result; the grant
 * and release paths keep reading the tracking map directly.
 *
 * <p>The rules, each of which mirrors something the coordinator already does:
 * <ul>
 *   <li><b>Holding a berth</b> is {@code assignedBerth != null} at stage AWAITING_TUG or later —
 *       the same test as {@code ForwardWalkInToPlayerBehaviour.freeCompatibleBerths} and the
 *       contracted pinned-berth check. A NEGOTIATING walk-in carries an {@code assignedBerth} too,
 *       but that one is the dialogue's ADVISORY candidate (reservation happens at the grant, S5), so
 *       it must never be shown as an occupant; an AWAITING_BERTH vessel's berth is null.</li>
 *   <li><b>The name</b> is the vessel's display name where it has one (walk-ins, S8) and its id
 *       otherwise (contracted vessels carry none).</li>
 *   <li><b>"Until"</b> survives only while it is still in the future. That drops a deadline the
 *       vessel has already served out (the berth frees a moment later), and it is also the second
 *       half of the torn-read guard described at {@code HarbourMasterAgent.portStatus}: the stale
 *       value a torn read can produce is a transit deadline that has, by construction, passed.</li>
 *   <li><b>Always the port's four berths</b>, in berth-id order, free ones included — the table
 *       has a fixed shape, and a berth id nobody knows is not a row.</li>
 * </ul>
 */
public final class PortStatusSnapshot {

    /**
     * The port's berth ids, sorted once. {@code BerthPositions.all()} is the single canonical
     * berth list but it is an unordered {@code Map.of}, and the event's contract is id order.
     */
    private static final List<String> BERTH_IDS = BerthPositions.all().keySet().stream().sorted().toList();

    private PortStatusSnapshot() {
    }

    /**
     * @param gameDay                       the 1-based game day of {@code nowSimMillis}
     * @param nowSimMillis                  the instant the snapshot is taken at
     * @param tracked                       every vessel the HarbourMaster tracks (any stage)
     * @param queuedVesselIdsInArrivalOrder the wait queue's vessel ids, oldest first
     * @param dockedUntil                   for a vessel that holds a berth: the sim-millis instant
     *                                      its service ends, or empty while it is not docked yet
     *                                      (or nobody can tell)
     * @return the four berth rows in id order, and the queue as display names in arrival order
     */
    public static PortStatusEvent build(int gameDay, long nowSimMillis, Collection<VesselTracking> tracked,
                                        List<String> queuedVesselIdsInArrivalOrder,
                                        Function<VesselTracking, OptionalLong> dockedUntil) {
        Map<String, VesselTracking> byVesselId = new HashMap<>();
        Map<String, VesselTracking> holderByBerth = new HashMap<>();
        if (tracked != null) {
            for (VesselTracking vessel : tracked) {
                if (vessel == null || vessel.vesselId() == null) {
                    continue;
                }
                byVesselId.put(vessel.vesselId(), vessel);
                if (holdsBerth(vessel)) {
                    holderByBerth.merge(vessel.assignedBerth(), vessel, PortStatusSnapshot::preferredHolder);
                }
            }
        }

        List<BerthRow> berths = new ArrayList<>(BERTH_IDS.size());
        for (String berthId : BERTH_IDS) {
            VesselTracking holder = holderByBerth.get(berthId);
            berths.add(holder == null
                    ? BerthRow.free(berthId)
                    : occupied(berthId, holder, nowSimMillis, dockedUntil));
        }

        List<String> queued = new ArrayList<>();
        if (queuedVesselIdsInArrivalOrder != null) {
            for (String vesselId : queuedVesselIdsInArrivalOrder) {
                if (vesselId != null) {
                    queued.add(displayName(vesselId, byVesselId.get(vesselId)));
                }
            }
        }
        return new PortStatusEvent(gameDay, berths, queued);
    }

    /** Granted, not merely discussed: see the class javadoc's first rule. */
    private static boolean holdsBerth(VesselTracking vessel) {
        return vessel.assignedBerth() != null
                && vessel.stage() != null
                && vessel.stage().ordinal() >= Stage.AWAITING_TUG.ordinal();
    }

    /**
     * Two holders of one berth should not exist (the grant paths check occupancy first), but this
     * is a display and must not flap between them from one tick to the next if it ever happens:
     * the vessel further along its stay wins, the lower vessel id breaks a tie. Deterministic for
     * any iteration order of the tracking map.
     */
    private static VesselTracking preferredHolder(VesselTracking a, VesselTracking b) {
        int byStage = Integer.compare(a.stage().ordinal(), b.stage().ordinal());
        if (byStage != 0) {
            return byStage > 0 ? a : b;
        }
        return a.vesselId().compareTo(b.vesselId()) <= 0 ? a : b;
    }

    private static BerthRow occupied(String berthId, VesselTracking holder, long nowSimMillis,
                                     Function<VesselTracking, OptionalLong> dockedUntil) {
        OptionalLong until = dockedUntil == null ? OptionalLong.empty() : dockedUntil.apply(holder);
        Long untilSimMillis = until != null && until.isPresent() && until.getAsLong() > nowSimMillis
                ? Long.valueOf(until.getAsLong())
                : null;
        return new BerthRow(berthId, holder.vesselId(), displayName(holder.vesselId(), holder),
                holder.channel(), holder.stage(), untilSimMillis, holder.weatherHeld());
    }

    /** The vessel's name where it has one, its id otherwise (also for an id nobody tracks). */
    private static String displayName(String vesselId, VesselTracking tracking) {
        String name = tracking == null || tracking.spec() == null ? null : tracking.spec().vesselName();
        return name == null || name.isBlank() ? vesselId : name;
    }
}
