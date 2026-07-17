package it.unige.portcommand.persistence.dto;

import java.util.List;
import java.util.Set;

/**
 * ONE flat record for every persisted active vessel — walk-in and contracted alike
 * (task 22 hard constraint: a {@code channel} discriminator field, NO
 * {@code @JsonTypeInfo}/{@code @JsonSubTypes} polymorphism; the LOADER branches on
 * {@code channel}, the JSON shape never does). Fields for the other channel are null.
 *
 * <p>Extends planning/22's sketch ({@code id, vesselType, channel, draft, tonnage,
 * currentPhase, contractRef, agreedPrice, personality, minAcceptablePrice, targetPrice,
 * maxWaitMinutes, roundsRemaining, negotiationStartedAt}) with what a real resume needs
 * (documented deviations, 2026-07-17): {@code length}/{@code cargoClass}/{@code
 * etaArrivalSimMillis} because {@code VesselSpec}'s validating constructor requires them;
 * the HM-side tracking fields ({@code stage}, {@code assignedBerth}, {@code assignedTugs},
 * {@code blocked}, {@code weatherHeld}) so the rebuilt HarbourMaster can re-track and — for
 * a tug-less AWAITING_TUG vessel — re-run the Contract Net; the vessel-side flow position
 * ({@code currentPhase} ∈ TRANSIT | DOCKED | DEPARTING + {@code phaseDeadlineSimMillis})
 * so a mid-transit vessel resumes instead of restarting; {@code dealHours} + position
 * because price alone cannot resume a docked stay; {@code minDurationHours} because task
 * 19b added it to the hidden-belief set; and {@code negotiationRound} (the raw round —
 * planning's {@code roundsRemaining} is a derived display figure). Times are sim-millis
 * longs, not ISO strings — this codebase has no calendar epoch ({@code EndOfDayEvent}
 * javadoc).
 *
 * <p><b>Hidden walk-in beliefs ARE here</b> ({@code personality} … {@code
 * minDurationHours}) — persisted verbatim so a loaded game is deterministic, still hidden
 * from the GUI: the save file is not a player surface, and the round-trip privacy test
 * asserts none of these values ever reaches an ACL message or bus event after a load.
 */
public record VesselStateDTO(
        String id,
        String vesselType,          // tanker | container_vessel | cargo_vessel | ferry | cruise_ship
        String channel,             // contracted | walk_in
        double draft,
        double length,
        int tonnage,
        String cargoClass,
        long etaArrivalSimMillis,
        String stage,               // VesselTracking.Stage name (HM-side view)
        String currentPhase,        // TRANSIT | DOCKED | DEPARTING (vessel-side flow position)
        long phaseDeadlineSimMillis,
        String assignedBerth,
        double dealPrice,
        int dealHours,
        List<String> assignedTugs,
        boolean blocked,
        boolean weatherHeld,
        double posX,
        double posY,
        double posHeading,
        // contracted-only (null when channel == walk_in)
        String contractRef,
        // walk-in-only hidden beliefs (null when channel == contracted)
        String personality,
        Double minAcceptablePrice,
        Double targetPrice,
        Integer maxWaitMinutes,
        Integer minDurationHours,
        Integer negotiationRound,
        Long negotiationStartedAtSimMillis) {

    public static final String CHANNEL_CONTRACTED = "contracted";
    public static final String CHANNEL_WALK_IN = "walk_in";

    public static final Set<String> PHASES = Set.of("TRANSIT", "DOCKED", "DEPARTING");
}
