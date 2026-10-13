package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * Whether the running scenario still has scripted events to fire (S16, 2026-10-13, ADR-20).
 *
 * <p>A scenario's script is written for ×1: its events are a few game minutes apart on purpose
 * (the storm gives the tanker thirty game seconds to hire its tugs before the wind turns). While
 * the script is pending the speed control and the day-length slider are therefore locked, and
 * this event is how the window learns when that is — the fact lives on the HarbourMaster's
 * scenario engine, and the GUI may only hear of it over the bus.
 *
 * <p>Published {@code true} when a world boots with scripted events still to come (a scenario
 * start, or a load of a scenario save taken mid-script), {@code false} when the last scripted
 * event has fired and on every boot that has nothing pending (free play, a finished script).
 * Daily contracted arrivals are not "the script" and never count.
 *
 * @param pending {@code true} while scripted events are still to come
 */
public record ScriptedWaveEvent(boolean pending) implements Event {
}
