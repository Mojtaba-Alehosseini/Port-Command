package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Current weather conditions, for the GUI — since S15 (2026-10-12) the Port table's
 * weather line, the only place the player sees them. Published by
 * {@code PeriodicWeatherBroadcastBehaviour} on every broadcast tick (task 24) and by a
 * scripted weather change at its scripted instant. Field names
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
