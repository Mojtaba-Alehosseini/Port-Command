package it.unige.portcommand.gui.events;

import java.util.List;

import it.unige.portcommand.util.Event;

/**
 * The autopilot policies the Assistant holds right now (S18a, ADR-23, decision N11). Published by
 * {@code PolicyParseBehaviour} whenever the list may have changed — a policy was set, the list was
 * cleared — and once when the Assistant starts, so a new or loaded world announces its own list
 * (a loaded game's policies are restored into the registry before the Assistant boots).
 *
 * <p>Before S18a the registry could be written and never read back: the player could not see what
 * was in force, and the first matching rule wins, so an early mistake silently shadowed every
 * later correction. The Settings form shows this list.
 *
 * @param descriptions each rule as the parser understood it ({@code PolicyRule.description()}),
 *                     in the order the rules are consulted; empty when there are none
 */
public record PolicyListEvent(List<String> descriptions) implements Event {

    public PolicyListEvent {
        descriptions = List.copyOf(descriptions);
    }
}
