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
 * {@code targetPrice}, {@code maxWaitMinutes}, {@code personality}, or {@code minDurationHours}
 * (task 19b's cargo-handling floor) — those are the walk-in's HIDDEN beliefs (see
 * {@code negotiation.WalkInState}, INVARIANTS.md "Agents / behaviours"). {@code durationHours}
 * here is the OBSERVABLE hours term on the wire (the vessel announces its preferred stay and
 * every counter carries its current hours), not the floor.
 * Reading them here would be the exact oral-exam attack rehearsed in job5 §5.4 Q2.
 * {@link WalkInDialogueSnapshotPrivacyTest} guards this reflectively.
 *
 * <p><b>The conversation clock (S14, ADR-18).</b> {@code timeUsedSec} and {@code timeLimitSec} are
 * the vessel's live counter exactly as the player's tab shows it: real seconds the conversation
 * has run, and the total it will run (elapsed + remaining, bonuses included). How long a vessel
 * waits is therefore ON SCREEN now — a deliberate S14 decision — and the Assistant may reason from
 * the same number the player sees. The price and personality beliefs behind it stay hidden; this
 * type still carries none of them. {@link #NO_TIME_LIMIT} means "no counter is known".
 *
 * <p><b>{@code vesselName} (task 16-M2)</b> is observable, NOT a hidden belief: a vessel's name
 * is painted on the hull, broadcast over AIS, and spoken in the vessel's own radio hail
 * ("Harbour Master, this is MV Genoa Star…"). The 16-M2 DCG resolves vocatives and definite
 * descriptions against it ({@code DialogueCtx} roster). It is the LAST component so the prior
 * 13-arg constructor still compiles — callers that have no name get {@code vesselId} as a
 * stand-in (see the compatibility constructor below).
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
        boolean threatenedWithdrawal,
        String vesselName,
        double timeLimitSec) {

    /** {@code timeLimitSec} when no conversation counter is known (hand-built snapshots, tests). */
    public static final double NO_TIME_LIMIT = 0.0;

    public WalkInDialogueSnapshot {
        Objects.requireNonNull(vesselId, "vesselId");
        Objects.requireNonNull(vesselType, "vesselType");
        Objects.requireNonNull(cargoClass, "cargoClass");
        Objects.requireNonNull(berthId, "berthId");
        vesselName = vesselName == null ? vesselId : vesselName;
    }

    /**
     * Backward-compatible constructor for callers that carry no display name: the name defaults
     * to {@code vesselId}. Keeps every pre-M2 construction site (and the {@code of}/{@code opening}
     * factories) compiling unchanged while the canonical 14-arg form carries a real name.
     */
    public WalkInDialogueSnapshot(String vesselId, String vesselType, double draft, double length, int tonnage,
                                  String cargoClass, String berthId, int durationHours, double lastVesselOffer,
                                  double lastPlayerOffer, int roundsUsed, double timeUsedSec,
                                  boolean threatenedWithdrawal) {
        this(vesselId, vesselType, draft, length, tonnage, cargoClass, berthId, durationHours, lastVesselOffer,
                lastPlayerOffer, roundsUsed, timeUsedSec, threatenedWithdrawal, vesselId);
    }

    /** The pre-S14 named form: no conversation counter ({@link #NO_TIME_LIMIT}). */
    public WalkInDialogueSnapshot(String vesselId, String vesselType, double draft, double length, int tonnage,
                                  String cargoClass, String berthId, int durationHours, double lastVesselOffer,
                                  double lastPlayerOffer, int roundsUsed, double timeUsedSec,
                                  boolean threatenedWithdrawal, String vesselName) {
        this(vesselId, vesselType, draft, length, tonnage, cargoClass, berthId, durationHours, lastVesselOffer,
                lastPlayerOffer, roundsUsed, timeUsedSec, threatenedWithdrawal, vesselName, NO_TIME_LIMIT);
    }

    /** A copy with the player's standing offer replaced — every other field, the counter included. */
    public WalkInDialogueSnapshot withLastPlayerOffer(double playerOffer) {
        return new WalkInDialogueSnapshot(vesselId, vesselType, draft, length, tonnage, cargoClass, berthId,
                durationHours, lastVesselOffer, playerOffer, roundsUsed, timeUsedSec, threatenedWithdrawal,
                vesselName, timeLimitSec);
    }

    /** A copy with the conversation counter replaced: elapsed and total, in real seconds (S14). */
    public WalkInDialogueSnapshot withTime(double usedSec, double limitSec) {
        return new WalkInDialogueSnapshot(vesselId, vesselType, draft, length, tonnage, cargoClass, berthId,
                durationHours, lastVesselOffer, lastPlayerOffer, roundsUsed, usedSec, threatenedWithdrawal,
                vesselName, limitSec);
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
        // S8 (GOAL 3): the spec's display name flows through — this one argument is what turned
        // the whole dormant name plumbing (tab title, DialogueCtx roster, Prolog ctx term, MED)
        // live. A null name still coalesces to the id in the compact constructor.
        return new WalkInDialogueSnapshot(vesselId, spec.vesselType(), spec.draft(), spec.length(),
                spec.tonnage(), spec.cargoClass(), berthId, durationHours, lastVesselOffer, lastPlayerOffer,
                roundsUsed, timeUsedSec, threatenedWithdrawal, spec.vesselName());
    }
}
