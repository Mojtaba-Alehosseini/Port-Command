package it.unige.portcommand.behaviours.coordination;

import it.unige.portcommand.agents.TugAgent;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.ontology.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Final leg: returns the tug to its home base after an escort, or after a
 * {@code CANCEL}. On arrival it goes {@code IDLE} and clears {@code currentJob};
 * {@link RefuelIfLowBehaviour} (still running) then tops the tank up if it is low.
 *
 * <p>Arrival hand-off ends the movement chain — no follow-on behaviour to add — so
 * it just resets state and calls {@code removeBehaviour(this)} LAST (nulls
 * {@code myAgent}; INVARIANTS.md).
 */
public final class ReturnToBaseBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(ReturnToBaseBehaviour.class);
    private static final long TICK_SIM_MILLIS = 1000L;

    private final TugAgent tug;
    private final Position base;
    private long lastSimMillis;

    public ReturnToBaseBehaviour(TugAgent tug) {
        super(tug, tug.simClock(), TICK_SIM_MILLIS);
        this.tug = tug;
        this.base = tug.basePosition();
        this.lastSimMillis = tug.simClock().nowSimMillis();
    }

    @Override
    protected void onSimTick() {
        if (tug.activeMovement() != this) {
            // S16 (B3, ADR-20): this leg was overtaken - the tug accepted a new job while still
            // on its way home (a cancelled tug carries no job and may bid). Accepting removes
            // this behaviour, so this line should never run; it is here because the alternative
            // was the worst bug in the fleet: two legs pulling one tug in opposite directions,
            // and this one, on arriving, clearing a job that was not its own - the escort never
            // completed and the tug stayed busy for the rest of the game.
            tug.removeBehaviour(this); // LAST: nulls myAgent
            return;
        }
        long now = simClock().nowSimMillis();
        double dtSimSeconds = (now - lastSimMillis) / 1000.0;
        lastSimMillis = now;
        if (dtSimSeconds <= 0.0) {
            return;
        }
        boolean arrived = tug.advanceToward(base, dtSimSeconds);
        tug.pushState();
        if (arrived) {
            log.info("{} back at base -> idle", tug.tugId());
            tug.setStatus(TugStatus.IDLE);
            tug.clearJob();
            tug.setActiveMovement(null);
            tug.announceAvailable(); // S16: a ship may be waiting for exactly this tug
            tug.removeBehaviour(this); // LAST: nulls myAgent
        }
    }
}
