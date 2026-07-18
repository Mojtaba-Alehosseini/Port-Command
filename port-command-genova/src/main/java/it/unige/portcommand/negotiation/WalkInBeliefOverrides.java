package it.unige.portcommand.negotiation;

/**
 * Scenario-pinned hidden-belief overrides for a scripted walk-in spawn (task 23).
 * Appended (optionally, found by type) to {@code WalkInVesselAgent}'s spawn args by
 * {@code AgentRoster.spawnWalkIn}; null fields fall back to the normal
 * {@code vessel_templates.json} draws (planning/23 "Notes": honor the overrides, fall
 * back to template ranges only when fields are null).
 *
 * <p><b>P-04 stands:</b> these are still hidden beliefs. They travel only through the
 * in-JVM constructor-args channel into {@code WalkInState} — never an ACL message,
 * never a bus event, never the GUI (the privacy round-trip test covers the persisted
 * copies like every other belief).
 */
public record WalkInBeliefOverrides(
        Personality personality,
        Double minAcceptablePrice,
        Double targetPrice) {
}
