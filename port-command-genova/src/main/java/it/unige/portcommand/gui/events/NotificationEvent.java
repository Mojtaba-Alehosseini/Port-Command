package it.unige.portcommand.gui.events;

import it.unige.portcommand.util.Event;

/**
 * The single HUD notice channel (planning/17: "THE ONLY {@code NotificationEvent} class" —
 * downstream tasks import this, they do not redefine it). Used here by {@code nlp.PolicyParser}
 * (parse-failure clarification toast) and {@code PolicyParseBehaviour} (policy-registered
 * confirmation) — there is no separate {@code POLICY_REGISTERED} channel (planning/10 §10.8).
 * See {@link HintButtonEvent}'s ownership note.
 *
 * @param text          the notice text
 * @param severity      how prominently the HUD should show it
 * @param simTimeMillis sim time the notice was raised
 */
public record NotificationEvent(String text, Severity severity, long simTimeMillis) implements Event {

    public enum Severity {
        INFO, WARNING, ERROR
    }
}
