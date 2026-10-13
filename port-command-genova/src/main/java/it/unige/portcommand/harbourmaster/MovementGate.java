package it.unige.portcommand.harbourmaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

import it.unige.portcommand.prolog.PrologQueries;

/**
 * The ONE weather check behind every ship movement (S16, 2026-10-13, ADR-20).
 *
 * <p>Before S16 the same three Prolog queries were written out by hand in three places
 * (the hold sweep, the tug-decline re-hire, the player-command gate) and were missing from every
 * other place a movement starts — the berth grants, the tug hiring, the queue release, the
 * restore re-dispatch, the departure. Each path therefore behaved a little differently in a storm,
 * and most did not look at the weather at all. Every one of them now asks here, so they cannot
 * drift apart again:
 *
 * <ul>
 *   <li>{@link #check} — may a vessel of this type START or CONTINUE a movement under this
 *       reading? Asked before a grant leads to tug hiring, before a Contract Net is launched or
 *       awarded, before the ship is told to proceed, before it is cleared to depart, and by the
 *       hold sweep for every ship that is waiting or moving.</li>
 *   <li>{@link #release} — may a vessel that is HELD be let go? The same check plus a wind
 *       margin ({@link #RELEASE_MARGIN_KNOTS}): cloudy wind wanders between 15 and 32 kn, a
 *       tanker's limit is 30, and without a margin a tanker would be held and released on
 *       alternate hours, hiring and cancelling an escort each time.</li>
 *   <li>{@link #calmForEveryType} — is it safe for every vessel type, margin included? What the
 *       weather agent reports as "clearing"; anything less is not good news for somebody.</li>
 * </ul>
 *
 * <p><b>Prolog decides, this class only asks and words the answer</b> (design rule 4): wind
 * against the type's limit is RULE R15, visibility R16, swell R17, the sea state R19. The margin
 * is not a rule: it compares the reading with the {@code wind_limit/2} fact that R15 itself uses
 * (queried, never copied), so the rule count stays 30. The verdict names the rules that failed,
 * which is what the Port table shows next to a held ship ("held (weather) [R15,R19]").
 *
 * <p><b>No reading is not "safe".</b> A verdict asked without a reading is closed and names no
 * rule. The HarbourMaster is given its first reading at boot on every path (a new game, a
 * scenario, a load), so this is a guard and not a state the game passes through — but it is the
 * difference between "the gate is open because nobody has told us yet" (the pre-S16 behaviour
 * after a load, for up to fifteen game minutes) and a gate.
 *
 * <p>Pure over {@link PrologQueries} (static, engine-locked, callable from any thread): no agent,
 * no clock, no bus. Unit-tested without a container ({@code MovementGateTest}).
 */
public final class MovementGate {

    /**
     * How far under its type's wind limit the wind must be before a held vessel is released:
     * a tanker (limit 30 kn) is held at 30 and resumes at 27 or less.
     */
    public static final double RELEASE_MARGIN_KNOTS = 3.0;

    public static final String R_WIND = "R15";
    public static final String R_VISIBILITY = "R16";
    public static final String R_SWELL = "R17";
    public static final String R_SEA_STATE = "R19";

    /** One weather reading: the four fields every weather message carries. */
    public record Reading(double windKnots, String visibility, double swell, String state) {
    }

    /**
     * The gate's answer.
     *
     * @param safe    whether the movement may go ahead
     * @param ruleIds the rules that said no, in rule order (R15, R16, R17, R19); empty when safe,
     *                and empty for the closed "no reading" verdict
     * @param reason  one line for the player, already carrying the rule tag; empty when safe
     */
    public record Verdict(boolean safe, List<String> ruleIds, String reason) {

        public Verdict {
            ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
            reason = reason == null ? "" : reason;
        }

        /** {@code [R15,R19]} — the bracket form every rule citation in the game uses; "" if none. */
        public String ruleTag() {
            return ruleIds.isEmpty() ? "" : "[" + String.join(",", ruleIds) + "]";
        }
    }

    private static final Verdict SAFE = new Verdict(true, List.of(), "");
    private static final Verdict NO_READING = new Verdict(false, List.of(), "no weather reading yet");

    /** The five vessel types, for {@link #calmForEveryType}. */
    private static final List<String> VESSEL_TYPES =
            List.of("tanker", "container_vessel", "cargo_vessel", "ferry", "cruise_ship");

    private MovementGate() {
    }

    /**
     * May a vessel of {@code vesselType} start or continue a movement under {@code reading}?
     * {@code null} reading: closed (see the class comment).
     */
    public static Verdict check(String vesselType, Reading reading) {
        return evaluate(vesselType, reading, 0.0);
    }

    /**
     * May a HELD vessel of {@code vesselType} be released under {@code reading}? As
     * {@link #check}, and the wind must also be {@link #RELEASE_MARGIN_KNOTS} or more under the
     * type's limit.
     */
    public static Verdict release(String vesselType, Reading reading) {
        return evaluate(vesselType, reading, RELEASE_MARGIN_KNOTS);
    }

    /** Whether {@link #release} passes for every vessel type — "the weather has really cleared". */
    public static boolean calmForEveryType(Reading reading) {
        if (reading == null) {
            return false;
        }
        for (String type : VESSEL_TYPES) {
            if (!release(type, reading).safe()) {
                return false;
            }
        }
        return true;
    }

    private static Verdict evaluate(String vesselType, Reading reading, double windMargin) {
        if (reading == null) {
            return NO_READING;
        }
        List<String> rules = new ArrayList<>(4);
        List<String> parts = new ArrayList<>(4);
        String wind = knots(reading.windKnots());
        if (!PrologQueries.windWithinLimit(vesselType, reading.windKnots())) {
            rules.add(R_WIND);
            parts.add("wind " + wind + " kn");
        } else if (windMargin > 0.0 && !underLimitBy(vesselType, reading.windKnots(), windMargin)) {
            // Within the limit, but not yet by the margin a release needs. Still R15's doing:
            // the comparison is against the very fact R15 reads.
            rules.add(R_WIND);
            parts.add("wind " + wind + " kn, resumes at " + resumeAt(vesselType, windMargin) + " kn or less");
        }
        if (!PrologQueries.visibilityOk(reading.visibility())) {
            rules.add(R_VISIBILITY);
            parts.add("visibility " + reading.visibility());
        }
        if (!PrologQueries.swellWithinLimit(reading.swell())) {
            rules.add(R_SWELL);
            parts.add("swell " + oneDecimal(reading.swell()) + " m");
        }
        if (PrologQueries.weatherStateUnsafe(reading.state())) {
            rules.add(R_SEA_STATE);
            parts.add("sea state " + reading.state());
        }
        if (rules.isEmpty()) {
            return SAFE;
        }
        return new Verdict(false, rules, String.join(", ", parts) + " [" + String.join(",", rules) + "]");
    }

    /** {@code wind + margin <= limit}; an unknown type has no limit and is never under it. */
    private static boolean underLimitBy(String vesselType, double windKnots, double margin) {
        OptionalDouble limit = PrologQueries.windLimit(vesselType);
        return limit.isPresent() && windKnots + margin <= limit.getAsDouble();
    }

    private static String resumeAt(String vesselType, double margin) {
        OptionalDouble limit = PrologQueries.windLimit(vesselType);
        return limit.isPresent() ? knots(limit.getAsDouble() - margin) : "?";
    }

    /** Whole knots where the reading is whole (it always is in play), one decimal otherwise. */
    private static String knots(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : oneDecimal(value);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
