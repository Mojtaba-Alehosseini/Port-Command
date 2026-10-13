package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.unige.portcommand.agents.TransitionMatrix;

/**
 * Pure model for the forecast the top bar's weather opens on a click (S16, 2026-10-13): for each
 * of the next few game hours, how likely the sea state is to be sunny, cloudy or stormy, given
 * the state it is in NOW. Plain Java — no widget toolkit import, no agent, no clock, no bus.
 *
 * <p><b>What it is.</b> The weather agent changes state once per game hour by one draw from a
 * fixed table, {@link TransitionMatrix#defaults()} — a Markov chain. The chance of each state
 * {@code n} hours from now is therefore the current state's row of that table multiplied by the
 * table {@code n - 1} more times: the matrix's {@code n}-th power, read off one row. Nothing is
 * guessed or fitted; these are the very numbers the game will roll against.
 *
 * <p><b>What it is not</b> — and {@link #NOTE} says so in the popup itself:
 * <ul>
 *   <li>A scenario can script a weather change, and while that script runs the random steps are
 *       suspended. The chain knows nothing of it, so during the storm scenario's opening the
 *       forecast describes weather that will not happen.</li>
 *   <li>It forecasts the STATE only. Whether a given ship may move also depends on the wind, the
 *       swell and the visibility, which follow the state loosely (a cloudy 31 kn holds a tanker;
 *       a sunny hour holds nobody). "Chance of a storm" is what the table can honestly give.</li>
 * </ul>
 *
 * <p>The read is of the static default table, which is what the weather agent runs on in every
 * shipped game; nothing is asked of an agent.
 */
public final class WeatherForecast {

    /** How many hours ahead the popup looks. */
    public static final int HOURS = 3;

    /** The one line under the table that says where the numbers come from and what they leave out. */
    public static final String NOTE =
            "From the weather model's transition table. A scenario's scripted weather is not included.";

    /** What the popup says instead of a table while no reading has arrived. */
    public static final String NO_READING = "No weather reading yet.";

    private WeatherForecast() {
    }

    /**
     * The state probabilities at one distance from now. Each is in 0..1 and the three add up to 1
     * (to floating-point accuracy).
     *
     * @param hoursAhead how many game hours from now, 1-based
     */
    public record Row(int hoursAhead, double sunny, double cloudy, double stormy) {

        /** {@code in 1 hour}, {@code in 2 hours}. */
        public String label() {
            return hoursAhead == 1 ? "in 1 hour" : "in " + hoursAhead + " hours";
        }

        /**
         * The three probabilities as whole percentages — sunny, cloudy, stormy — that add up to
         * exactly 100.
         *
         * <p>Rounding each one on its own does not always: three hours on from a cloudy sky the
         * table gives 63.36 / 30.38 / 6.26, which rounds to 63 + 30 + 6 = 99, and a row of a
         * forecast that does not add up reads as a mistake. So each value is cut to its whole
         * part and the points still missing go to the values that lost most by the cut, largest
         * fraction first (the largest-remainder method; that row becomes 63 / 31 / 6); a tie goes
         * to the earlier state. No value ends a full point away from its exact figure.
         */
        public int[] percents() {
            double[] exact = {tidy(sunny), tidy(cloudy), tidy(stormy)};
            int[] whole = new int[exact.length];
            double[] lost = new double[exact.length];
            int total = 0;
            for (int i = 0; i < exact.length; i++) {
                whole[i] = (int) Math.floor(exact[i]);
                lost[i] = exact[i] - whole[i];
                total += whole[i];
            }
            for (int missing = 100 - total; missing > 0; missing--) {
                int most = -1;
                for (int i = 0; i < lost.length; i++) {
                    if (lost[i] > 0.0 && (most < 0 || lost[i] > lost[most])) {
                        most = i;
                    }
                }
                if (most < 0) {
                    break; // nothing was cut: the values do not add up to 100, and that is not hidden
                }
                whole[most]++;
                lost[most] = 0.0;
            }
            return whole;
        }

        /** A probability as a percentage with the floating-point dust removed (12.999999999999998
         * is 13, and must not be cut to 12). */
        private static double tidy(double probability) {
            return Math.rint(probability * 1e11) / 1e9;
        }
    }

    /** The table's column heads, in the order of a {@link Row}'s values: sunny, cloudy, stormy. */
    public static List<String> columns() {
        return TransitionMatrix.STATES;
    }

    /**
     * The forecast from {@code state} for the next {@code hours} game hours, by the table the
     * weather agent uses. No rows for a state the table does not know — {@code null}, blank, or a
     * word that is none of sunny / cloudy / stormy — and for {@code hours} below 1: with no
     * reading there is nothing to forecast from, and the popup says that instead of a table.
     */
    public static List<Row> forState(String state, int hours) {
        return forState(TransitionMatrix.defaults(), state, hours);
    }

    /** As {@link #forState(String, int)}, over a given table. */
    public static List<Row> forState(TransitionMatrix matrix, String state, int hours) {
        int from = indexOf(state);
        if (from < 0 || hours < 1 || matrix == null || matrix.rows() == null) {
            return List.of();
        }
        double[] chance = new double[TransitionMatrix.STATES.size()];
        chance[from] = 1.0; // now: certainly the state the reading names
        List<Row> rows = new ArrayList<>(hours);
        for (int hour = 1; hour <= hours; hour++) {
            chance = oneHourOn(matrix, chance);
            rows.add(new Row(hour, chance[0], chance[1], chance[2]));
        }
        return List.copyOf(rows);
    }

    /**
     * One step of the chain: the chance of each state an hour after {@code chance}. A state the
     * table has no row for stays as it is, which is what {@link TransitionMatrix#nextState} does
     * with it.
     */
    private static double[] oneHourOn(TransitionMatrix matrix, double[] chance) {
        List<String> states = TransitionMatrix.STATES;
        double[] next = new double[states.size()];
        for (int from = 0; from < states.size(); from++) {
            if (chance[from] == 0.0) {
                continue;
            }
            Map<String, Double> row = matrix.rows().get(states.get(from));
            for (int to = 0; to < states.size(); to++) {
                double step = row == null ? (from == to ? 1.0 : 0.0) : row.getOrDefault(states.get(to), 0.0);
                next[to] += chance[from] * step;
            }
        }
        return next;
    }

    private static int indexOf(String state) {
        return state == null ? -1 : TransitionMatrix.STATES.indexOf(state.trim().toLowerCase(Locale.ROOT));
    }
}
