package it.unige.portcommand.behaviours.coordination;

import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.ontology.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First escort leg: moves the tug from its current position to the vessel pickup
 * point. A sim-time ticker — each tick advances by the sim-seconds elapsed on the
 * {@link it.unige.portcommand.util.SimClock} since the previous tick, so a driver
 * advances the tug purely by advancing the clock (no wall sleeps). Because the step
 * uses the same {@link it.unige.portcommand.agents.TugMath} conversion as the bid
 * ETA, arrival coincides with the quoted {@code eta_minutes}.
 *
 * <p>On arrival it hands off to {@link EscortToBerthBehaviour}: do the work + add the
 * next behaviour FIRST, {@code removeBehaviour(this)} LAST (that call nulls
 * {@code myAgent}; anything after it NPEs — INVARIANTS.md).
 */
public final class TransitToVesselBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(TransitToVesselBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L; // sample the clock each sim-second

    private final TugAgent tug;
    private final Position pickup;
    private long lastSimMillis;

    public TransitToVesselBehaviour(TugAgent tug, Position pickup) {
        super(tug, tug.simClock(), TICK_SIM_MILLIS);
        this.tug = tug;
        this.pickup = pickup;
        this.lastSimMillis = tug.simClock().nowSimMillis();
    }

    @Override
    protected void onSimTick() {
        long now = simClock().nowSimMillis();
        double dtSimSeconds = (now - lastSimMillis) / 1000.0;
        lastSimMillis = now;
        if (dtSimSeconds <= 0.0) {
            return; // clock hasn't advanced since last tick — nothing to move
        }
        boolean arrived = tug.advanceToward(pickup, dtSimSeconds);
        tug.pushState();
        if (arrived) {
            log.info("{} reached vessel pickup {} -> escorting to berth", tug.tugId(), pickup);
            tug.setStatus(TugStatus.ESCORTING);
            EscortToBerthBehaviour escort = new EscortToBerthBehaviour(tug);
            tug.setActiveMovement(escort);
            tug.addBehaviour(escort);
            tug.removeBehaviour(this); // LAST: nulls myAgent
        }
    }
}
