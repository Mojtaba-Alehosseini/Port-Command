package it.unige.portcommand.gui.model;

import java.util.Locale;

import it.unige.portcommand.gui.events.WeatherChangeEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S16 (2026-10-13): the top bar's weather line as text. {@link HudWeatherModel} is plain Java (no
 * Swing/AWT import — {@code PortTableModelTest}'s last case scans it), so this runs on the JUnit
 * thread; the wiring to the bus and to the bar's label is {@code GameScreenCompositionTest}'s
 * "top bar" block and {@code GuiPanelsSubscriptionLifecycleTest}.
 */
class HudWeatherModelTest {

    private static final WeatherChangeEvent CALM = new WeatherChangeEvent(18.4, "good", 0.5, "sunny", false);
    private static final WeatherChangeEvent STORM = new WeatherChangeEvent(41.5, "poor", 4.2, "stormy", true);

    @Test
    void beforeTheFirstReadingTheLineIsAPlaceholderNotCalmWeather() {
        HudWeatherModel model = new HudWeatherModel();

        assertEquals("—", model.text());
        assertFalse(model.hasReading());
        assertNull(model.state(), "no state: there is nothing to forecast from");
        assertNull(model.tooltip(), "a placeholder has nothing more to say");
    }

    @Test
    void aReadingIsWindStateVisibilityAndSwellFromTheEventsOwnFields() {
        HudWeatherModel model = new HudWeatherModel();

        assertTrue(model.apply(CALM));

        assertEquals("18 kn · sunny · visibility good · swell 0.5 m", model.text());
        assertTrue(model.hasReading());
        assertEquals("sunny", model.state());
        assertEquals("18 kn · sunny · visibility good · swell 0.5 m (click for the forecast)", model.tooltip(),
                "the tooltip is the whole line — it restores what a narrow bar cut — and says it is a control");
    }

    @Test
    void theWindIsRoundedToWholeKnotsAndTheSwellPrintedToOneDecimal() {
        assertEquals("42 kn · stormy · visibility poor · swell 4.2 m", HudWeatherModel.text(STORM));
        assertEquals("0 kn · cloudy · visibility fair · swell 0.0 m",
                HudWeatherModel.text(new WeatherChangeEvent(0.0, "fair", 0.0, "cloudy", false)),
                "fair is the weather agent's third visibility word, and a flat sea is still a reading");
        assertEquals("30 kn · cloudy · visibility good · swell 4.0 m",
                HudWeatherModel.text(new WeatherChangeEvent(30.0, "good", 4.0, "cloudy", true)));
        assertEquals("27 kn · sunny · visibility good · swell 1.3 m",
                HudWeatherModel.text(new WeatherChangeEvent(27.0, "good", 1.26, "sunny", false)),
                "one decimal: the limit the swell is read against is 4.0");
    }

    @Test
    void aFieldTheEventDoesNotCarryIsLeftOutNotPrintedBlank() {
        assertEquals("18 kn · swell 0.0 m",
                HudWeatherModel.text(new WeatherChangeEvent(18.0, null, 0.0, null, false)));
        assertEquals("18 kn · sunny · swell 1.0 m",
                HudWeatherModel.text(new WeatherChangeEvent(18.0, " ", 1.0, "sunny", false)));
        assertEquals("18 kn · visibility poor · swell 1.0 m",
                HudWeatherModel.text(new WeatherChangeEvent(18.0, "poor", 1.0, "", false)));
        assertEquals("18 kn · sunny · visibility good",
                HudWeatherModel.text(new WeatherChangeEvent(18.0, "good", Double.NaN, "sunny", false)),
                "a swell that is not a number is not printed as one");
        assertEquals("—", HudWeatherModel.text(null));
    }

    @Test
    void anEqualReadingIsNotAChangeAndADifferentOneIs() {
        // The weather agent re-announces every fifteen game minutes whether or not anything moved.
        HudWeatherModel model = new HudWeatherModel();

        assertTrue(model.apply(CALM));
        assertFalse(model.apply(new WeatherChangeEvent(18.4, "good", 0.5, "sunny", false)),
                "a separately built but equal reading needs no repaint");
        assertTrue(model.apply(STORM));
        assertEquals("stormy", model.state());
        assertEquals("42 kn · stormy · visibility poor · swell 4.2 m", model.text());
        assertThrows(NullPointerException.class, () -> model.apply(null));
    }

    @Test
    void clearGoesBackToThePlaceholderSoAnotherWorldsWeatherNeverStands() {
        HudWeatherModel model = new HudWeatherModel();
        assertFalse(model.clear(), "nothing to forget yet");
        model.apply(STORM);

        assertTrue(model.clear());

        assertEquals("—", model.text());
        assertFalse(model.hasReading());
        assertNull(model.state());
        assertNull(model.tooltip());
        assertFalse(model.clear(), "clearing an already blank line is not a change");

        // The next world's opening reading — even one equal to the old world's — is shown again.
        assertTrue(model.apply(STORM));
        assertEquals("42 kn · stormy · visibility poor · swell 4.2 m", model.text());
    }

    @Test
    void theNumbersDoNotFollowTheDefaultLocale() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            // German writes a decimal comma, like a Danish or Italian desktop.
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("0,5", String.format("%.1f", 0.5), "non-vacuity: the default locale is now German");
            assertEquals("18 kn · sunny · visibility good · swell 0.5 m", HudWeatherModel.text(CALM));

            // A locale whose default-format digits are not ASCII.
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("th-TH-u-nu-thai"));
            assertNotEquals("18", String.format("%.0f", 18.4), "non-vacuity: this default locale has its own digits");
            assertEquals("18 kn · sunny · visibility good · swell 0.5 m", HudWeatherModel.text(CALM));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }
}
