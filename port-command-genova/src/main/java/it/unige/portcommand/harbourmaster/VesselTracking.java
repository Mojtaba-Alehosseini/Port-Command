package it.unige.portcommand.harbourmaster;

import java.util.List;

import it.unige.portcommand.ontology.VesselSpec;
import jade.core.AID;

/**
 * HarbourMaster's live view of one vessel currently in play — tracked from first
 * contact (contracted REQUEST or walk-in opening PROPOSE) through departure.
 * Immutable; state transitions replace the map entry (mirrors
 * {@code agents.BerthOccupancy.withStatus}). Keyed by {@code vesselId} in
 * {@code HarbourMasterAgent.activeVessels} ({@code ConcurrentHashMap} — multiple
 * behaviours read/mutate it: auto-flow dispatch, walk-in relay, CNP award,
 * weather/emergency holds).
 *
 * <p>Holds the full {@link VesselSpec} (not just the task file's bare "type" field)
 * because the weather safety gate and the walk-in deal-confirmed berth grant both
 * need to re-run {@code PrologQueries.isCompatible}/{@code operationSafe}, which
 * require draft/length/tonnage, not just the type atom. {@code aid} and
 * {@code blocked} likewise extend the task file's original field list — both are
 * needed to correlate inbound messages (no {@code vessel_id} on every reply) and to
 * record a customs-flagged hold.
 *
 * <p>{@code weatherHeld} (task 24, ADR-14): marks a vessel whose escort was
 * CANCELled by a weather hold, so the weather-CLEAR re-dispatch sweeps exactly
 * those. Without it a held vessel is indistinguishable from one whose FIRST tug
 * CNP is still in flight ({@code AWAITING_TUG} + empty tug list in both cases),
 * and a clear-signal sweep would fire a duplicate CFP at fresh vessels —
 * double-award, double-charge.
 */
public record VesselTracking(
        String vesselId,
        VesselSpec spec,
        Channel channel,
        Stage stage,
        AID aid,
        String assignedBerth,
        List<String> assignedTugs,
        boolean blocked,
        boolean weatherHeld) {

    public enum Channel { CONTRACTED, WALK_IN }

    public enum Stage { ARRIVING, NEGOTIATING, AWAITING_TUG, IN_TRANSIT, DOCKED, DEPARTING }

    public VesselTracking {
        assignedTugs = assignedTugs == null ? List.of() : List.copyOf(assignedTugs);
    }

    public static VesselTracking arriving(String vesselId, VesselSpec spec, Channel channel, AID aid) {
        return new VesselTracking(vesselId, spec, channel, Stage.ARRIVING, aid, null, List.of(), false, false);
    }

    /** Convenience accessor matching the task file's "type" field name. */
    public String vesselType() {
        return spec.vesselType();
    }

    public VesselTracking withStage(Stage newStage) {
        return new VesselTracking(vesselId, spec, channel, newStage, aid, assignedBerth, assignedTugs,
                blocked, weatherHeld);
    }

    public VesselTracking withBerth(String berthId) {
        return new VesselTracking(vesselId, spec, channel, stage, aid, berthId, assignedTugs,
                blocked, weatherHeld);
    }

    public VesselTracking withTugs(List<String> tugs) {
        return new VesselTracking(vesselId, spec, channel, stage, aid, assignedBerth, tugs,
                blocked, weatherHeld);
    }

    public VesselTracking withBlocked(boolean newBlocked) {
        return new VesselTracking(vesselId, spec, channel, stage, aid, assignedBerth, assignedTugs,
                newBlocked, weatherHeld);
    }

    public VesselTracking withWeatherHeld(boolean newWeatherHeld) {
        return new VesselTracking(vesselId, spec, channel, stage, aid, assignedBerth, assignedTugs,
                blocked, newWeatherHeld);
    }
}
