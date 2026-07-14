package it.unige.portcommand.behaviours.negotiation;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.behaviours.SimTickerBehaviour;
import it.unige.portcommand.negotiation.WalkInState;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fires a single timeout withdrawal if no deal closes within {@code maxWaitMinutes}
 * of the negotiation start. A {@link SimTickerBehaviour} on a 5-sim-second cadence
 * (the wall interval derives from {@code SimClock.simSecondsToWallMs(5)}, so it tracks
 * the player's clock speed); the deadline is measured in sim-time. Once the deal is
 * {@code concluded} by another path, it removes itself.
 */
public final class TimeoutWithdrawalBehaviour extends SimTickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(TimeoutWithdrawalBehaviour.class);
    private static final long TICK_SIM_MILLIS = 5000L; // 5 sim-seconds
    private static final long SIM_MILLIS_PER_MINUTE = 60_000L;

    private final AtomicReference<WalkInState> stateRef;
    private final String conversationId;
    private final AtomicBoolean concluded;

    public TimeoutWithdrawalBehaviour(Agent agent, SimClock simClock, AtomicReference<WalkInState> stateRef,
                                      String conversationId, AtomicBoolean concluded) {
        super(agent, simClock, TICK_SIM_MILLIS);
        this.stateRef = stateRef;
        this.conversationId = conversationId;
        this.concluded = concluded;
    }

    @Override
    protected void onSimTick() {
        if (concluded.get()) {
            myAgent.removeBehaviour(this);
            return;
        }
        WalkInState state = stateRef.get();
        if (state == null || state.negotiationStartedAtSimMillis() == 0L) {
            return; // negotiation not opened yet
        }
        long elapsed = simClock().nowSimMillis() - state.negotiationStartedAtSimMillis();
        if (elapsed > (long) state.maxWaitMinutes() * SIM_MILLIS_PER_MINUTE) {
            concluded.set(true);
            log.info("{}: negotiation timed out ({} sim-min) -> withdraw", myAgent.getLocalName(),
                    state.maxWaitMinutes());
            myAgent.addBehaviour(new WithdrawalBehaviour(myAgent, stateRef, conversationId, "timeout"));
            myAgent.removeBehaviour(this);
        }
    }
}
