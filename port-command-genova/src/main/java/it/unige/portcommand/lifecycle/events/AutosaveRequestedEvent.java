package it.unige.portcommand.lifecycle.events;

import it.unige.portcommand.util.Event;

/**
 * The end-of-day settlement for {@code day} is complete and a save of the
 * current state would be consistent (task 24). Published by
 * {@code DayRolloverCoordinator} after {@code EndOfDayCompletedEvent}, before
 * {@code DayRolloverEvent}, exactly once per day (the coordinator's own EOD
 * idempotency guard covers this event too).
 *
 * <p><b>No consumer exists yet.</b> Task 22's {@code SaveLoadManager} subscribes
 * here for the autosave (the {@code day} field doubles as its
 * {@code lastAutoSaveDay} dedupe guard — planning/22 notes the ownership,
 * dated 2026-07-17). Task 24 deliberately builds no persistence.
 *
 * @param day the game day whose settlement just completed
 */
public record AutosaveRequestedEvent(int day) implements Event {
}
