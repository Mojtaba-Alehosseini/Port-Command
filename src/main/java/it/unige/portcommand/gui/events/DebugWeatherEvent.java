package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Debug-menu weather override (task 24, visual-checkpoint-#5 request): forces the
 * WeatherAgent's current snapshot so a storm — rare by design under the recalibrated
 * Markov chain (steady-state ≈4%) — can be demonstrated on demand. Published by
 * {@code MainWindow}'s Debug menu ("Force storm" / "Clear weather"); sole consumer is
 * {@code DebugWeatherOverrideBehaviour} on the WeatherAgent. The override sets the
 * shared snapshot verbatim; the normal broadcast tick then picks it up, so alerts,
 * holds, GUI chip and notifications all flow through the REAL pipeline — nothing is
 * simulated GUI-side. The Markov chain keeps evolving FROM the forced state, so a
 * forced storm naturally subsides unless cleared explicitly.
 *
 * @param wind       forced wind (kn)
 * @param visibility {@code good|fair|poor}
 * @param swell      forced swell (m)
 * @param state      {@code sunny|cloudy|stormy}
 */
public record DebugWeatherEvent(int wind, String visibility, double swell, String state)
        implements Event {
}
