package it.unige.portcommand.gui.model;

import java.util.Locale;
import java.util.Objects;

import it.unige.portcommand.gui.events.WeatherChangeEvent;

/**
 * Pure model for the top bar's weather text (S16, 2026-10-13): the last reading as one line —
 * {@code 18 kn · sunny · visibility good · swell 0.5 m} — or {@value #PLACEHOLDER} while there is
 * none. Plain Java, no widget toolkit import and no thread assertion; {@code HUDPanel} keeps the
 * EDT discipline, so tests call it straight from the JUnit thread (the split {@code HUDModel} and
 * {@code PortTableModel} use).
 *
 * <p><b>Why the top bar.</b> Until S16 the weather was the last line of the Port table, in the
 * narrow left column, and it printed no swell. Since S16 a deal may be closed in any weather and
 * the ship then waits outside until the reading allows the movement — so the reading is what the
 * player checks before answering a hail, and it has to be on screen whichever column they are
 * looking at. The swell joined the line because it now varies (a storm draws 3–5 m against a 4 m
 * limit) and holds ships on its own (RULE R17).
 *
 * <p><b>Unknown is not calm.</b> Before the first reading, and again after {@link #clear()} (a
 * world was replaced — the next one announces its own weather when it boots), the text is the
 * placeholder. The previous world's reading is never left standing: with ships held by the
 * weather, a stale "sunny" would be a wrong answer to the question the line exists for.
 *
 * <p>The words are the weather agent's own vocabulary, taken from the event's fields unchanged;
 * numbers are formatted with {@link Locale#ROOT} so the bar reads the same on every desktop.
 */
public final class HudWeatherModel {

    /** Shown while no reading has arrived. */
    public static final String PLACEHOLDER = "—";
    /** Joins the parts of the line. */
    public static final String SEPARATOR = " · ";
    /** What the tooltip adds after the full reading: the line is a control, and says so. */
    public static final String CLICK_HINT = " (click for the forecast)";

    /** {@code null} until the first reading, and again after {@link #clear()}. */
    private WeatherChangeEvent reading;

    /**
     * Takes a reading.
     *
     * @return whether it differs from the one held — an equal reading needs no repaint (the
     *         weather agent re-announces every fifteen game minutes whether or not anything moved)
     */
    public boolean apply(WeatherChangeEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.equals(reading)) {
            return false;
        }
        reading = event;
        return true;
    }

    /**
     * Forgets the reading — the world it described is gone.
     *
     * @return whether there was one to forget
     */
    public boolean clear() {
        if (reading == null) {
            return false;
        }
        reading = null;
        return true;
    }

    public boolean hasReading() {
        return reading != null;
    }

    /** The line the bar shows. */
    public String text() {
        return text(reading);
    }

    /**
     * The sea state of the reading held ({@code sunny}, {@code cloudy}, {@code stormy}) — what
     * the forecast is computed from — or {@code null} while there is no reading.
     */
    public String state() {
        return reading == null ? null : reading.state();
    }

    /**
     * The full line plus the hint that it can be clicked — also what restores the text when the
     * bar had to cut it short — or {@code null} while there is no reading (a placeholder has
     * nothing more to say).
     */
    public String tooltip() {
        return reading == null ? null : text(reading) + CLICK_HINT;
    }

    /**
     * {@code 18 kn · sunny · visibility good · swell 0.5 m}, or {@value #PLACEHOLDER} for no
     * reading. The wind is rounded to whole knots (it is whole in play), the swell printed to one
     * decimal; a state or a visibility the event does not carry is left out rather than printed
     * as a blank.
     */
    public static String text(WeatherChangeEvent weather) {
        if (weather == null) {
            return PLACEHOLDER;
        }
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%.0f kn", weather.windKnots()));
        if (present(weather.state())) {
            text.append(SEPARATOR).append(weather.state().trim());
        }
        if (present(weather.visibility())) {
            text.append(SEPARATOR).append("visibility ").append(weather.visibility().trim());
        }
        if (Double.isFinite(weather.swell())) {
            text.append(SEPARATOR).append(String.format(Locale.ROOT, "swell %.1f m", weather.swell()));
        }
        return text.toString();
    }

    private static boolean present(String word) {
        return word != null && !word.isBlank();
    }
}
