package it.unige.portcommand.behaviours.coordination;

import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugStatus;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the tank topped up. Polls once per {@link #CHECK_WALL_MILLIS}: when the tug
 * is {@code IDLE}, at base, and below {@link TugAgent#LOW_FUEL_THRESHOLD}, it goes
 * {@code REFUELING} and schedules a {@link WakerBehaviour} for
 * {@link #REFUEL_SIM_SECONDS} sim-seconds; on wake the tank is full again and it
 * returns to {@code IDLE}.
 *
 * <p>A polling {@link TickerBehaviour} (not a {@code CyclicBehaviour}) because the
 * trigger condition is a state that has to be re-checked periodically — nothing posts
 * an event to wake a cyclic. (task 08 §8.4 wrote "Cyclic"; the stub was a Ticker and
 * a periodic condition-poll is the right tool — doc-truth.) The {@code REFUELING}
 * guard means a refuel-in-progress is never restarted.
 */
public final class RefuelIfLowBehaviour extends TickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(RefuelIfLowBehaviour.class);
    private static final long CHECK_WALL_MILLIS = 1000L;
    private static final long REFUEL_SIM_SECONDS = 60L;

    private final TugAgent tug;

    public RefuelIfLowBehaviour(TugAgent tug) {
        super(tug, CHECK_WALL_MILLIS);
        this.tug = tug;
    }

    @Override
    protected void onTick() {
        if (tug.status() != TugStatus.IDLE || !tug.atBase() || tug.fuelState() >= TugAgent.LOW_FUEL_THRESHOLD) {
            return;
        }
        log.info("{} low fuel ({}) at base -> refuelling for {} sim-s",
                tug.tugId(), tug.fuelState(), REFUEL_SIM_SECONDS);
        tug.setStatus(TugStatus.REFUELING);
        long wallMs = Math.max(1L, tug.simClock().simSecondsToWallMs(REFUEL_SIM_SECONDS));
        tug.addBehaviour(new WakerBehaviour(tug, wallMs) {
            @Override
            protected void onWake() {
                TugAgent t = (TugAgent) myAgent;
                t.refuelFull();
                t.setStatus(TugStatus.IDLE);
                log.info("{} refuelled to full", t.tugId());
            }
        });
    }
}
