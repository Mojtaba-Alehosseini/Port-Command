package it.unige.portcommand.agents;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import it.unige.portcommand.agents.InitArgs.WeatherInitArgs.ScriptedWeather;
import it.unige.portcommand.scenario.ScriptedWeatherWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The weather chain's stepping logic, with no JADE in it (S16, 2026-10-13, ADR-20, decisions
 * B5, B6 and B8). The weather agent owns one; {@code WeatherEvolutionBehaviour} is only the
 * ticker that hands it the clock.
 *
 * <p><b>B6: one step per game hour, on the hour, counted in SIM time.</b> Before S16 the step
 * was "one per tick" of a ticker armed with the WALL length of a game hour. A speed change
 * therefore lost steps (the tick armed at &times;1 covered four game hours at &times;4) or
 * added one (the tick armed at &times;4 fired after fifteen game minutes at &times;1), a tick
 * that fell into a pause was dropped, and no step ever fell on the hour. Here the steps belong
 * to the clock, not to the ticker: {@link #advanceTo} is told what time it is and takes every
 * step whose hour boundary has been reached since the last call, in order, each stamped with
 * its own boundary instant. How often, how irregularly or how late it is called changes nothing
 * about which steps happen or what they draw, so the weather of a seed is the same at every
 * speed and across every pause. The first boundary is the first full hour strictly after the
 * instant the stepper was built, so a game loaded at 08:20 steps at 09:00, not at 09:20.
 *
 * <p><b>B5: a scenario's own weather is left alone.</b> A step whose boundary falls inside the
 * scenario's {@link ScriptedWeatherWindow} is SUSPENDED: it leaves the reading exactly as the
 * script set it. It still makes every random draw a normal step makes, on both streams — the
 * owner's decision: the script overrides the outcome, the chain keeps its cadence, so what
 * comes after the window does not depend on how many steps the window swallowed. For the
 * shipped storm scenario (seed 20260320) this is the 09:00 step, inside the window
 * 08:16:30&nbsp;&hellip;&nbsp;09:33: its roll is 0.9532, which is {@code stormy} from any
 * state — before S16 that was a second, unscripted storm twenty-seven game minutes after the
 * "Storm clearing" banner.
 *
 * <p><b>B8: swell and visibility vary a little, from their own stream.</b> The pre-S16 readings
 * were fixed per state (stormy 3.5&nbsp;m, cloudy {@code fair}), so RULE R17 (swell over
 * 4.0&nbsp;m) could never fire and RULE R16 (visibility) only ever failed together with R19.
 * A stormy step now draws its swell from {@value #STORMY_SWELL_MIN}&ndash;{@value
 * #STORMY_SWELL_MAX}&nbsp;m (one decimal) and a cloudy step has {@code poor} visibility
 * {@value #CLOUDY_POOR_VISIBILITY_CHANCE} of the time. Those draws come from a SEPARATE random
 * stream, and every step takes the same two values from it whatever state it lands in, so
 * neither stream's position ever depends on the weather.
 *
 * <p><b>The main stream is drawn exactly as before S16</b> — one {@code nextDouble()} for the
 * state, then the wind drift's one {@code nextInt} — so every seed's state and wind sequence is
 * the one it always was; only the instants (now the full hours) and the two detail fields
 * changed.
 *
 * <p>Not thread-safe and does not need to be: one agent thread calls it. It never touches the
 * agent's shared cell either — the caller passes the current reading in and writes the result
 * back — which is what lets the tests drive it with a list of instants and nothing else.
 */
public final class WeatherStepper {

    private static final Logger log = LoggerFactory.getLogger(WeatherStepper.class);

    /** One weather step per game hour. */
    public static final long STEP_SIM_MILLIS = 3_600_000L;

    /**
     * The most steps one call takes. A call normally finds none or one; a late tick after a
     * speed-up finds a few. Three game days in one call means the clock was moved under us, and
     * the agent thread should not spin through years of weather in one go: the rest is taken on
     * the following calls (a sim-minute apart), still one per boundary, so nothing is skipped.
     */
    static final int MAX_STEPS_PER_CALL = 72;

    static final double STORMY_SWELL_MIN = 3.0;
    static final double STORMY_SWELL_MAX = 5.0;
    static final double CLOUDY_POOR_VISIBILITY_CHANCE = 0.10;

    private static final long SIM_MILLIS_PER_MINUTE = 60_000L;
    private static final int MAX_WIND_STEP_KN = 6; // per-step wind drift cap (persistence)

    private final TransitionMatrix matrix;
    private final Random rng;
    private final Random detailRng;
    private final ScriptedWeatherWindow scriptedWindow;
    private final List<ScriptedWeather> overrides;
    private int nextOverride = 0;
    private long nextStepAtSimMillis;

    /**
     * @param matrix                the Markov transition matrix
     * @param rng                   the main stream ({@code RandomSource.forStream("weather")}):
     *                              state and wind
     * @param detailRng             the detail stream
     *                              ({@code RandomSource.forStream("weather-detail")}): swell and
     *                              visibility. {@code null} keeps the fixed pre-S16 readings
     *                              (stormy 3.5&nbsp;m, cloudy {@code fair}); the game always
     *                              passes one, the null form is what lets a test show that the
     *                              main sequence does not depend on it
     * @param scriptedWindow        the scenario's scripted-weather window, or {@code null}
     *                              (free play, and scenarios that script no weather)
     * @param overrides             the legacy init-args scripted snapshots (never used by the
     *                              shipped game, whose scenarios script weather through the
     *                              event engine; kept for {@code WeatherAgentIT}); may be empty
     * @param constructedAtSimMillis the sim instant the weather agent was set up at; the first
     *                              step is the first full hour strictly after it
     */
    public WeatherStepper(TransitionMatrix matrix, Random rng, Random detailRng,
                          ScriptedWeatherWindow scriptedWindow, List<ScriptedWeather> overrides,
                          long constructedAtSimMillis) {
        this.matrix = Objects.requireNonNull(matrix, "matrix");
        this.rng = Objects.requireNonNull(rng, "rng");
        this.detailRng = detailRng;
        this.scriptedWindow = scriptedWindow;
        this.overrides = (overrides == null ? List.<ScriptedWeather>of() : overrides).stream()
                .sorted(Comparator.comparingInt(ScriptedWeather::simMinute)).toList();
        this.nextStepAtSimMillis = (constructedAtSimMillis / STEP_SIM_MILLIS + 1) * STEP_SIM_MILLIS;
    }

    /**
     * Takes every step whose hour boundary lies at or before {@code nowSimMillis} and has not
     * been taken yet, oldest first, starting from {@code current}.
     *
     * @return the reading after those steps; {@code current} ITSELF (the same object) when no
     *         step took effect — none was due, or every due one was suspended — so the caller
     *         can tell by identity whether there is anything to write and to broadcast
     */
    public WeatherSnapshot advanceTo(long nowSimMillis, WeatherSnapshot current) {
        Objects.requireNonNull(current, "current");
        WeatherSnapshot w = current;
        int taken = 0;
        while (nextStepAtSimMillis <= nowSimMillis) {
            if (taken == MAX_STEPS_PER_CALL) {
                log.warn("weather is {} game hours behind the clock after {} steps in one call; "
                                + "the rest follow on the next calls",
                        (nowSimMillis - nextStepAtSimMillis) / STEP_SIM_MILLIS + 1, taken);
                break;
            }
            w = stepAt(nextStepAtSimMillis, w);
            nextStepAtSimMillis += STEP_SIM_MILLIS;
            taken++;
        }
        return w;
    }

    /** The boundary instant of the next step that has not been taken yet. */
    public long nextStepAtSimMillis() {
        return nextStepAtSimMillis;
    }

    private WeatherSnapshot stepAt(long boundary, WeatherSnapshot w) {
        // Legacy init-args override: applied at the first step at or after its sim-minute, IN
        // PLACE of that step's Markov draw — it draws nothing, as it never did. (It also wins
        // over the window: the two are never combined, the game passes no overrides.)
        if (nextOverride < overrides.size()
                && overrides.get(nextOverride).simMinute() <= boundary / SIM_MILLIS_PER_MINUTE) {
            ScriptedWeather override = overrides.get(nextOverride);
            nextOverride++;
            WeatherSnapshot s = override.snapshot();
            log.info("scripted weather (due sim-min {}) applied at sim-min {}: wind={} state={}",
                    override.simMinute(), boundary / SIM_MILLIS_PER_MINUTE, s.wind(), s.state());
            return new WeatherSnapshot(s.wind(), s.visibility(), s.swell(), s.state(), boundary);
        }

        // Main stream, in the pre-S16 order: the state roll, then the drift's one nextInt.
        String nextState = matrix.nextState(w.state(), rng.nextDouble());
        int wind = switch (nextState) {
            case "cloudy" -> drift(w.wind(), 15, 32);
            case "stormy" -> drift(w.wind(), 30, 55);
            default -> drift(w.wind(), 5, 20);
        };
        // Detail stream: always both values, used or not, so its position is the step count.
        boolean detailed = detailRng != null;
        double swellRoll = detailed ? detailRng.nextDouble() : 0.0;
        double visibilityRoll = detailed ? detailRng.nextDouble() : 1.0;

        if (scriptedWindow != null && scriptedWindow.covers(boundary)) {
            // B5: the draws above are spent, their outcome is not used (see the class comment).
            log.debug("weather step at sim-min {} suspended: scripted weather is in force",
                    boundary / SIM_MILLIS_PER_MINUTE);
            return w;
        }
        log.debug("weather Markov step {} -> {}", w.state(), nextState);
        return switch (nextState) {
            case "cloudy" -> new WeatherSnapshot(wind,
                    detailed && visibilityRoll < CLOUDY_POOR_VISIBILITY_CHANCE ? "poor" : "fair",
                    1.5, "cloudy", boundary);
            case "stormy" -> new WeatherSnapshot(wind, "poor",
                    detailed ? stormySwell(swellRoll) : 3.5, "stormy", boundary);
            default -> new WeatherSnapshot(wind, "good", 0.5, "sunny", boundary);
        };
    }

    /** {@code roll} in [0,1) mapped onto the stormy swell range, rounded to one decimal. */
    private static double stormySwell(double roll) {
        double metres = STORMY_SWELL_MIN + (STORMY_SWELL_MAX - STORMY_SWELL_MIN) * roll;
        return Math.round(metres * 10.0) / 10.0;
    }

    /**
     * Moves {@code prevWind} toward a random target inside {@code [lo,hi]} by at most
     * {@link #MAX_WIND_STEP_KN} knots, then clamps into the band. WITHIN a band this gives the
     * wind persistence that stops the threshold flapping (small step, no teleport). Across a
     * DISJOINT band (a state transition, e.g. sunny[5,20]→stormy[30,55]) the final clamp overrides
     * the step cap and snaps to the nearer band edge — a single legitimate crossing per state
     * change, which is the intended "weather just changed" signal, not the intra-state flapping the
     * cap targets (adversarial review L2, 2026-07-17). Moved here unchanged from
     * {@code WeatherEvolutionBehaviour} in S16.
     */
    private int drift(int prevWind, int lo, int hi) {
        int target = lo + rng.nextInt(hi - lo + 1);
        int step = Math.max(-MAX_WIND_STEP_KN, Math.min(MAX_WIND_STEP_KN, target - prevWind));
        return Math.max(lo, Math.min(hi, prevWind + step));
    }
}
