package it.unige.portcommand.agents;

import java.util.Optional;

import it.unige.portcommand.harbourmaster.MovementGate;
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
 * <p><b>WORSENED</b> fires on a genuine crossing between two consecutive
 * broadcast readings: any dimension degrades (a mixed change reads as WORSENED:
 * safety first). Same-band wind drift (33→31&nbsp;kn) is silent, which is what
 * kills the every-broadcast-URGENT behaviour observed at the task-18
 * checkpoint.
 *
 * <p><b>CLEARED means it is really safe again (S16, 2026-10-13, ADR-20, decision B4).</b>
 * Until S16 a clear was the mirror image of a worsening: ANY dimension recovering, the wind
 * band included. So 46&nbsp;→&nbsp;44&nbsp;kn in the middle of a storm told the player
 * "Weather clearing: 44 kn, stormy" and sent the HarbourMaster {@code weather_clear}, and so
 * did leaving the {@code stormy} state at 31&nbsp;kn, which is still over a tanker's limit —
 * good news that was not good news for anybody. A clear is now a transition only when the previous
 * reading was NOT calm and the current one IS, where calm is
 * {@link MovementGate#calmForEveryType}: the HarbourMaster's own release check (wind at least
 * {@value MovementGate#RELEASE_MARGIN_KNOTS}&nbsp;kn under the type's limit, visibility, swell
 * and sea state all safe) passing for every vessel type — with today's limits, 27&nbsp;kn or
 * less. It is the same gate and the same margin that decide whether a held ship is let go, so
 * "clearing" and "ships resume" cannot disagree. A partial improvement — a band drop inside a
 * storm, the storm ending while the wind is still too strong for some type — is routine: no
 * alert. No ship waits for this signal: every periodic snapshot makes the HarbourMaster re-run
 * its release check ship by ship, so a cargo vessel that may sail at 31&nbsp;kn is not kept
 * back until the tankers can. Whether a clearing is then ANNOUNCED is the broadcast behaviour's
 * call ({@code PeriodicWeatherBroadcastBehaviour.announce}): only when a worsening was announced
 * before it, so "clearing" never arrives out of the blue.
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
        boolean visUp = visUnsafe(current.visibility()) && !visUnsafe(prev.visibility());
        boolean swellUp = swellUnsafe(current.swell()) && !swellUnsafe(prev.swell());
        boolean stateUp = stateUnsafe(current.state()) && !stateUnsafe(prev.state());

        if (windUp || visUp || swellUp || stateUp) {
            return Optional.of(Direction.WORSENED);
        }
        // S16 (B4): not "something recovered" but "it is safe for every vessel type again" —
        // see the class comment. Cannot coincide with a worsening: each of the four degradations
        // above leaves the current reading not calm (30 kn or more, or unsafe visibility, swell
        // or sea state), so the order of the two tests is not a tie-break.
        if (!calm(prev) && calm(current)) {
            return Optional.of(Direction.CLEARED);
        }
        return Optional.empty();
    }

    /** {@link MovementGate#calmForEveryType} for a snapshot: a held ship of any type may go. */
    static boolean calm(WeatherSnapshot w) {
        return MovementGate.calmForEveryType(
                new MovementGate.Reading(w.wind(), w.visibility(), w.swell(), w.state()));
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
