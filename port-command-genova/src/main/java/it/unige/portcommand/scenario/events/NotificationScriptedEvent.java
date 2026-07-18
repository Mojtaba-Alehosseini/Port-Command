package it.unige.portcommand.scenario.events;

import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.scenario.GameContext;

/**
 * Scripted banner: publishes task 17's canonical {@code gui.events.NotificationEvent}
 * — this task defines NO second notification class (planning/23 hard note).
 *
 * <p>JSON severity vocabulary is the §23.4 sketch's {@code INFO | WARN | EMERGENCY},
 * mapped onto the canonical {@code NotificationEvent.Severity} (INFO → INFO,
 * WARN → WARNING, EMERGENCY → ERROR — the strip's highest tier; dated 2026-07-18).
 */
public record NotificationScriptedEvent(long simTimeSeconds, String severity, String text)
        implements ScriptedEvent {

    @Override
    public void apply(GameContext ctx) {
        ctx.eventBus().publish(new NotificationEvent(text, mappedSeverity(),
                ctx.simClock().nowSimMillis()));
    }

    /** The canonical severity this scripted banner publishes with. */
    public NotificationEvent.Severity mappedSeverity() {
        return switch (severity) {
            case "WARN" -> NotificationEvent.Severity.WARNING;
            case "EMERGENCY" -> NotificationEvent.Severity.ERROR;
            default -> NotificationEvent.Severity.INFO;
        };
    }
}
