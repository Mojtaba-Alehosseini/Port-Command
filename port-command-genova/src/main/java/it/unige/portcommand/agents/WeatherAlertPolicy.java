package it.unige.portcommand.agents;

import java.util.Optional;

import it.unige.portcommand.prolog.PrologQueries;

/**
 * When is a weather change ALERT-WORTHY, and in which direction? (task 24 — the
 * cadence/threshold/priority rework filed at planning/24.)
 *
 * <p>The severity of a reading is a composite of the four dimensions the
 * HarbourMaster's safety gate evaluates: the discrete wind band
 * (30/35/40/45&nbsp;kn — the {@code rules_weather.pl} {@code wind_limit}
 * threshold set), visibility, the swell limit, and the unsafe-state flag. The
 * last three are answered by the SAME Prolog rules the HarbourMaster's
 * {@code HandleWeatherAlertBehaviour} gate evaluates
 * ({@code visibilityOk} (R16, inside {@code operationSafe}) /
 * {@code swellWithinLimit} / {@code weatherStateUnsafe}, all cached pure
 * goals), so the agent's notion of "alert-worthy" can never drift from the
 * hub's notion of "unsafe" (design rule: Prolog first). Visibility was the
 * adversarial review's M1: without it, a fog-only worsening never held anyone,
 * and a fog-only recovery never fired the clear signal — a held vessel could
 * strand until some unrelated crossing happened by.
 *
 * <p>A transition fires ONLY on a genuine crossing between two consecutive
 * broadcast readings — worsening when any dimension degrades, clearing when
 * any dimension recovers and none degrades (a mixed change reads as WORSENED:
 * safety first). Same-band wind drift (33→31&nbsp;kn) is silent, which is what
 * kills the every-broadcast-URGENT behaviour observed at the task-18
 * checkpoint.
 */
public final class WeatherAlertPolicy {

    // WIND_THRESHOLDS ({30, 35, 40, 45}) was REMOVED in S5 (audit P2-4, wiring R18): it was a
    // Java DUPLICATE of rules_weather.pl's threshold set, exactly the drift the "single source of
    // truth in Prolog" rule exists to prevent. The band is now derived from R18's own
    // threshold_crossed/2 enumeration via PrologQueries.windThresholdsCrossed — same numbers,
    // now the RULE'S numbers.

    public enum Direction { WORSENED, CLEARED }

    private WeatherAlertPolicy() {
    }

    /**
     * The alert direction for the change {@code prev → current}, or empty when nothing
     * alert-worthy happened. {@code prev == null} (the first reading) is the baseline and
     * never alerts.
     */
    public static Optional<Direction> transition(WeatherSnapshot prev, WeatherSnapshot current) {
        if (prev == null) {
            return Optional.empty();
        }
        // Two engine round-trips, not four (S5 adversarial finding 7): each windBand call is
        // an uncached JPL query under the global engine lock, so evaluate once per snapshot.
        int currentBand = windBand(current.wind());
        int prevBand = windBand(prev.wind());
        boolean windUp = currentBand > prevBand;
        boolean windDown = currentBand < prevBand;
        boolean visUp = visUnsafe(current.visibility()) && !visUnsafe(prev.visibility());
        boolean visDown = !visUnsafe(current.visibility()) && visUnsafe(prev.visibility());
        boolean swellUp = swellUnsafe(current.swell()) && !swellUnsafe(prev.swell());
        boolean swellDown = !swellUnsafe(current.swell()) && swellUnsafe(prev.swell());
        boolean stateUp = stateUnsafe(current.state()) && !stateUnsafe(prev.state());
        boolean stateDown = !stateUnsafe(current.state()) && stateUnsafe(prev.state());

        if (windUp || visUp || swellUp || stateUp) {
            return Optional.of(Direction.WORSENED);
        }
        if (windDown || visDown || swellDown || stateDown) {
            return Optional.of(Direction.CLEARED);
        }
        return Optional.empty();
    }

    /**
     * How many alert thresholds {@code wind} sits at or above (0..4) — RULE R18, called: the
     * band IS the size of {@code threshold_crossed/2}'s enumeration for this wind. Uncached on
     * the facade side (continuous input); {@code transition} evaluates it once per snapshot —
     * two engine round-trips per evolution tick — negligible.
     */
    static int windBand(double wind) {
        return PrologQueries.windThresholdsCrossed(wind).size();
    }

    private static boolean visUnsafe(String visibility) {
        return !PrologQueries.visibilityOk(visibility);
    }

    private static boolean swellUnsafe(double swell) {
        return !PrologQueries.swellWithinLimit(swell);
    }

    private static boolean stateUnsafe(String state) {
        return PrologQueries.weatherStateUnsafe(state);
    }
}
