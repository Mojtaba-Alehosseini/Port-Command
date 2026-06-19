package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Published once per simulated minute. Task 03 declares this type and owns its
 * single publisher (the live wall-clock tick driver is wired in task 24, per the
 * Option-B split); GUI panels (task 17+) subscribe to it to drive the clock
 * display. The other 17 {@code gui.events} types are created in task 17.
 *
 * @param simMillis sim time at the tick
 * @param gameDay   1-based game day
 * @param simHour   hour of the sim day, 0–23
 * @param simMinute minute of the sim hour, 0–59
 */
public record SimClockTickEvent(long simMillis, int gameDay, int simHour, int simMinute)
        implements Event {
}
