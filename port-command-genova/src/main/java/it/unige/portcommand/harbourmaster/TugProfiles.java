package it.unige.portcommand.harbourmaster;

import java.util.Map;

/**
 * S19 (playtest 5, PT39): each tug's own fare, fuel cost per km and top speed. Playtest 5 found
 * every tug bidding about EUR 480 with an ETA of about 29 minutes: all four wait at one base
 * ({@link TugBasePositions}), and all four had the same fare, fuel price and speed, so only a few
 * metres of distance told their bids apart - the auction looked fake, and R12 (score = fuel x 100
 * - cost x 0.1 - ETA) was in practice decided by fuel alone. Now there is a small, cheap, slow
 * tug, a big, fast, dear one, and two between them.
 *
 * <p>The fleet's AVERAGES are what the four tugs had - EUR 350 fare, EUR 12 per km, 12 knots - so
 * the economy is calibrated as before: {@code ExpenseRules}' average tug fee (the Assistant's
 * cost) and the channel leg a ship makes under escort ({@code MovementControl}, at the fleet's
 * average speed) do not move. R12 is unchanged. The save keeps no profile: a tug's comes from
 * its id, so a load gives each tug its own again.
 */
public final class TugProfiles {

    /** What one tug charges and how fast it goes. */
    public record Profile(double baseFare, double fuelCostPerKm, double topSpeedKnots) {
    }

    private static final Map<String, Profile> PROFILES = Map.of(
            "tug_1", new Profile(290.0, 9.0, 10.0),   // the small one: cheap and slow
            "tug_2", new Profile(330.0, 11.0, 11.5),
            "tug_3", new Profile(370.0, 13.0, 12.5),
            "tug_4", new Profile(410.0, 15.0, 14.0)); // the big one: dear and fast

    private TugProfiles() {
    }

    /** The profile of {@code tugId}; an unknown id is a bug in the caller. */
    public static Profile of(String tugId) {
        Profile profile = PROFILES.get(tugId);
        if (profile == null) {
            throw new IllegalArgumentException("no tug profile for " + tugId);
        }
        return profile;
    }

    /** The full {@code tug_id -> profile} table. */
    public static Map<String, Profile> all() {
        return PROFILES;
    }
}
