package it.unige.portcommand.assistant;

import java.util.Objects;

import it.unige.portcommand.ontology.VesselSpec;

/**
 * Observable-only projection of an in-progress walk-in negotiation — the sole input to
 * {@link RecommendationAlgorithm}. Every field here is something the Harbour Master (and
 * therefore the Assistant, which never outranks the player's own knowledge) could plausibly
 * see on screen: the vessel's public spec, the berth under discussion, the offers exchanged so
 * far, and how long the dialogue has run.
 *
 * <p><b>v1.1 hard constraint (P-04):</b> this type must NOT expose {@code minAcceptablePrice},
 * {@code targetPrice}, {@code maxWaitMinutes}, or {@code personality} — those are the walk-in's
 * HIDDEN beliefs (see {@code negotiation.WalkInState}, INVARIANTS.md "Agents / behaviours").
 * Reading them here would be the exact oral-exam attack rehearsed in job5 §5.4 Q2.
 * {@link WalkInDialogueSnapshotPrivacyTest} guards this reflectively.
 */
public record WalkInDialogueSnapshot(
        String vesselId,
        String vesselType,
        double draft,
        double length,
        int tonnage,
        String cargoClass,
        String berthId,
        int durationHours,
        double lastVesselOffer,
        double lastPlayerOffer,
        int roundsUsed,
        double timeUsedSec,
        boolean threatenedWithdrawal) {

    public WalkInDialogueSnapshot {
        Objects.requireNonNull(vesselId, "vesselId");
        Objects.requireNonNull(vesselType, "vesselType");
        Objects.requireNonNull(cargoClass, "cargoClass");
        Objects.requireNonNull(berthId, "berthId");
    }

    /** The round-1 opening move: the vessel has proposed, the player has not countered yet. */
    public static WalkInDialogueSnapshot opening(String vesselId, VesselSpec spec, String berthId,
                                                 int durationHours, double openingOffer) {
        return of(vesselId, spec, berthId, durationHours, openingOffer, 0.0, 1, 0.0, false);
    }

    /** Builds a snapshot from the vessel's public spec plus the dialogue's observable progress. */
    public static WalkInDialogueSnapshot of(String vesselId, VesselSpec spec, String berthId, int durationHours,
                                            double lastVesselOffer, double lastPlayerOffer, int roundsUsed,
                                            double timeUsedSec, boolean threatenedWithdrawal) {
        Objects.requireNonNull(spec, "spec");
        return new WalkInDialogueSnapshot(vesselId, spec.vesselType(), spec.draft(), spec.length(),
                spec.tonnage(), spec.cargoClass(), berthId, durationHours, lastVesselOffer, lastPlayerOffer,
                roundsUsed, timeUsedSec, threatenedWithdrawal);
    }
}
