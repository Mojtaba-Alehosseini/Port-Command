package it.unige.portcommand.gui.events;

import java.util.Set;

import it.unige.portcommand.util.Event;

/**
 * Who is negotiating in which chat tab: the Assistant's own statement, and the ONLY thing the
 * window shows it from (S17a, ADR-21, decisions A2 and A10).
 *
 * <p>The autopilot switch is global - it is the Settings toggle - and "Take over" is per tab: a
 * negotiation the player took over stays the player's until it ends, whatever the switch does.
 * So a tab belongs to the Assistant exactly when the switch is on and the tab is not in
 * {@code takenOver}. The event carries the WHOLE state, not a change, so a listener that missed
 * one is right again at the next, and a tab that opens later is right from its first render.
 *
 * <p>Published by the {@code AssistantAgent}: when it boots (a new game, a load, a scenario
 * start), when the switch changes, and when a tab is taken over or a taken-over negotiation
 * ends. A window that is built after the world missed the boot statement and starts from
 * {@link #initial} of the session's autopilot setting - which is what the agent booted from.
 * Before S17a the chat panel kept a copy of the Settings toggle and set it to
 * "off" on every load, while the respawned agent read the SAVED value: after loading an
 * autopilot-ON save the window offered Hint and a free input while the Assistant was negotiating
 * (backlog R-07).
 *
 * @param autopilotOn whether the autopilot is switched on
 * @param takenOver   the dialogue ids the player took over from it; never {@code null}
 */
public record AutopilotOwnershipEvent(boolean autopilotOn, Set<String> takenOver) implements Event {

    public AutopilotOwnershipEvent {
        takenOver = takenOver == null ? Set.of() : Set.copyOf(takenOver);
    }

    /** The state nobody has announced yet for a world whose autopilot is {@code autopilotOn}. */
    public static AutopilotOwnershipEvent initial(boolean autopilotOn) {
        return new AutopilotOwnershipEvent(autopilotOn, Set.of());
    }

    /** Whether the Assistant - not the player - is negotiating in {@code dialogueId}'s tab. */
    public boolean assistantOwns(String dialogueId) {
        return autopilotOn && !takenOver.contains(dialogueId);
    }
}
