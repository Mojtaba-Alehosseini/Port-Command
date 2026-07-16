package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Current weather conditions, for the map overlay (task 18) and HUD weather
 * indicator. No producer exists yet — {@code WeatherAgent} (task 09) only
 * broadcasts an ACL INFORM; a future task wires a translation (or
 * {@code WeatherAgent} itself) to publish this alongside it. Field names
 * mirror the real broadcast content ({@code PeriodicWeatherBroadcastBehaviour},
 * task 09) rather than inventing new vocabulary.
 *
 * @param windKnots        current wind speed
 * @param visibility       {@code "good"} | {@code "poor"} (WeatherAgent's own vocabulary)
 * @param swell            metres
 * @param state            {@code "sunny"} | {@code "cloudy"} | {@code "stormy"} (WeatherAgent's own vocabulary)
 * @param thresholdCrossed {@code true} only when this update is a 30/35/40/45 kn threshold-crossing alert
 */
public record WeatherChangeEvent(double windKnots, String visibility, double swell, String state,
                                  boolean thresholdCrossed) implements Event {
}
