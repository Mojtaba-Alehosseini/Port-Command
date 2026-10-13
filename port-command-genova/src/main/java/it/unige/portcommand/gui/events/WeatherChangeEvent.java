package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Current weather conditions, on the bus. Since S16 (2026-10-13) the top bar shows them
 * ({@code HUDPanel} — in S15 they were the Port table's last line) and opens its forecast from
 * the {@code state}; and the Assistant keeps the last one, to add a note to a Hint when the
 * weather would keep that vessel waiting outside after a deal ({@code assistant.WeatherNote}).
 *
 * <p>Published by {@code PeriodicWeatherBroadcastBehaviour} on every broadcast tick (task 24),
 * by a scripted weather change at its scripted instant, and — S16 — by {@code GameSession} once
 * at every world boot and when a window reports ready, from the HarbourMaster's opening reading,
 * so a subscriber has the weather from the first instant instead of after the weather agent's
 * first broadcast. Field names mirror the real broadcast content
 * ({@code PeriodicWeatherBroadcastBehaviour}, task 09) rather than inventing new vocabulary.
 *
 * @param windKnots        current wind speed
 * @param visibility       {@code "good"} | {@code "fair"} | {@code "poor"} (WeatherAgent's own
 *                         vocabulary; RULE R16 accepts good and fair. S16: this line used to list
 *                         good and poor only — {@code fair} has always been sent)
 * @param swell            metres
 * @param state            {@code "sunny"} | {@code "cloudy"} | {@code "stormy"} (WeatherAgent's own vocabulary)
 * @param thresholdCrossed {@code true} only when this update is a 30/35/40/45 kn threshold-crossing alert
 */
public record WeatherChangeEvent(double windKnots, String visibility, double swell, String state,
                                  boolean thresholdCrossed) implements Event {
}
