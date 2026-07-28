package it.unige.portcommand.scenario.events;

import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.scenario.GameContext;
import jade.core.behaviours.OneShotBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scripted refuel completion: sets {@code tug_&lt;tugId&gt;} back to full/IDLE (the
 * §23.4 "back to AVAILABLE") and posts an INFO banner so the return is visible.
 *
 * <p>The state flip runs as a {@link OneShotBehaviour} <b>added to the tug agent</b> —
 * {@code addBehaviour} from another thread is the JADE-sanctioned way to run code on
 * that agent's own thread, so this never mutates tug state from the HarbourMaster
 * thread. The handler touches only the captured final reference, never
 * {@code myAgent} (the task-19 nulled-{@code myAgent} lesson).
 *
 * <p>Note the interplay with {@code RefuelIfLowBehaviour}: a scenario that boots a tug
 * as REFUELING restores it IDLE-with-low-tank (task-22 normalisation), and the tug's
 * own refuel loop may finish before this event fires — the flip is then a no-op
 * belt-and-braces and the banner still marks the scripted moment.
 */
public record TugRefuelCompleteEvent(long simTimeSeconds, int tugId) implements ScriptedEvent {

    private static final Logger log = LoggerFactory.getLogger(TugRefuelCompleteEvent.class);

    @Override
    public void apply(GameContext ctx) {
        String localName = "tug_" + tugId;
        if (ctx.directory() == null) {
            log.warn("scripted refuel-complete: no agent directory — skipping {}", localName);
            return;
        }
        ctx.directory().byName(localName)
                .filter(TugAgent.class::isInstance)
                .map(TugAgent.class::cast)
                .ifPresentOrElse(tug -> {
                    tug.addBehaviour(new OneShotBehaviour(tug) {
                        @Override
                        public void action() {
                            if (tug.status() == TugStatus.IDLE || tug.status() == TugStatus.REFUELING) {
                                tug.refuelFull();
                                tug.setStatus(TugStatus.IDLE);
                            }
                        }
                    });
                    ctx.eventBus().publish(new NotificationEvent(
                            "Tug " + tugId + " refuelled and back in service.",
                            NotificationEvent.Severity.INFO, ctx.simClock().nowSimMillis()));
                    log.info("scripted refuel-complete: {} back in service", localName);
                }, () -> log.warn("scripted refuel-complete: {} not in directory — skipping", localName));
    }
}
