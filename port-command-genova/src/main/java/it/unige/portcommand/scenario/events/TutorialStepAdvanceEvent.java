package it.unige.portcommand.scenario.events;

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

    /**
     * S18b (playtest 3, PT1): only the step event. The "Tutorial N/5" chip this used to publish
     * as well was the placeholder from before the overlay existed (see the class note); with the
     * card on screen it said everything twice, and it was still there - stale - after the
     * scenario had ended. The overlay is the one rendering now.
     */
    @Override
    public void apply(GameContext ctx) {
        ctx.eventBus().publish(new TutorialStepAdvancedEvent(step, text));
        log.info("tutorial step {} advanced", step);
    }
}
