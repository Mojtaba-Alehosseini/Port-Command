package it.unige.portcommand.gui.events;

import it.unige.portcommand.artifacts.PolicyRule;
import it.unige.portcommand.util.Event;

/**
 * Fired by {@code nlp.PolicyParser} (planning/10 §10.5b) when a player-typed policy expression
 * parses successfully — NOT by a Rasa intent (the second Rasa pipeline for policy expressions
 * was cut, PROJECT_DEFINITION §9 item 6). {@code PolicyParseBehaviour} (§10.8) subscribes and
 * registers the rule. See {@link HintButtonEvent}'s ownership note.
 *
 * @param rule the parsed, ready-to-register policy
 */
public record PolicyParsedEvent(PolicyRule rule) implements Event {
}
