package it.unige.portcommand.negotiation;

/**
 * How long a walk-in waits for the player, in conversation time (S14, ADR-18): a base by vessel
 * type, shifted by personality, extended a little by every message the player sends. Pure numbers
 * — {@link it.unige.portcommand.util.ConversationClock} does the timekeeping.
 *
 * <p>The values are Moji's playability decision (2026-10-11), sized for a human who types and
 * thinks: nothing under a minute and a half, nothing over five minutes left on the clock.
 */
public final class PatiencePolicy {

    /** Each player message that reaches the vessel buys this much more patience. */
    public static final long REPLY_BONUS_MILLIS = 30_000L;
    /** No bonus ever leaves more than this on the clock. */
    public static final long CAP_REMAINING_MILLIS = 300_000L;

    private static final int PERSONALITY_SHIFT_SECONDS = 30;

    private PatiencePolicy() {
    }

    /** The opening patience for a vessel of {@code vesselType} and {@code personality}. */
    public static long budgetMillis(String vesselType, Personality personality) {
        return (baseSeconds(vesselType) + modifierSeconds(personality)) * 1_000L;
    }

    /** Ferries are in a hurry; a cargo ship can wait. Exactly the five PROJECT_DEFINITION types. */
    static int baseSeconds(String vesselType) {
        return switch (vesselType) {
            case "ferry" -> 120;
            case "container_vessel" -> 150;
            case "tanker", "cruise_ship" -> 180;
            case "cargo_vessel" -> 240;
            default -> throw new IllegalArgumentException("unknown vessel type: " + vesselType);
        };
    }

    /** A desperate ship waits longer, an aggressive one walks sooner. */
    static int modifierSeconds(Personality personality) {
        return switch (personality) {
            case DESPERATE -> PERSONALITY_SHIFT_SECONDS;
            case AGGRESSIVE -> -PERSONALITY_SHIFT_SECONDS;
            case NEUTRAL -> 0;
        };
    }
}
