package it.unige.portcommand.scenario.events;

import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.scenario.GameContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advances the tutorial to {@code step}, publishing {@link TutorialStepAdvancedEvent}
 * on the bus for the {@code TutorialOverlay} to consume.
 *
 * <p><b>Reconciliation (2026-07-18):</b> {@code TutorialOverlay} is task-21-owned and
 * does not exist yet, so alongside the bus event this ALSO publishes the canonical
 * {@code gui.events.NotificationEvent} as a placeholder banner ("Tutorial N/5 — …" on
 * the notification strip), making the tutorial followable today. Task 21's real
 * overlay replaces the placeholder rendering, not this event.
 */
public record TutorialStepAdvanceEvent(long simTimeSeconds, int step, String text)
        implements ScriptedEvent {

    private static final Logger log = LoggerFactory.getLogger(TutorialStepAdvanceEvent.class);

    /** The tutorial's step count (§3.15: 5 steps). */
    public static final int TOTAL_STEPS = 5;

    @Override
    public void apply(GameContext ctx) {
        ctx.eventBus().publish(new TutorialStepAdvancedEvent(step, text));
        ctx.eventBus().publish(new NotificationEvent(
                "Tutorial " + step + "/" + TOTAL_STEPS + " — " + text,
                NotificationEvent.Severity.INFO, ctx.simClock().nowSimMillis()));
        log.info("tutorial step {} advanced", step);
    }
}
