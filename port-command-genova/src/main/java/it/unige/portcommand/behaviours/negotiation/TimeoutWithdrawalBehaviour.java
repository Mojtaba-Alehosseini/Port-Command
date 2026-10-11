package it.unige.portcommand.behaviours.negotiation;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.negotiation.WalkInState;
import it.unige.portcommand.util.ConversationClock;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fires a single timeout withdrawal when this walk-in's patience runs out.
 *
 * <p><b>S14 (ADR-18): patience is conversation time, not game time.</b> This used to be a
 * {@code SimTickerBehaviour} measuring sim-minutes against the hidden {@code maxWaitMinutes}
 * belief — on a clock that runs a day in minutes, so a ferry left after 29-50 real seconds. It
 * now watches the dialogue's {@link ConversationClock.Conversation}: real seconds, frozen while
 * the game is paused or this tab's Hint is waiting for the model, extended a little by every
 * message the player sends, and untouched by the speed control. The withdrawal itself is
 * unchanged — the same {@code "timeout"} reason, hence the same outcome and reputation penalty.
 *
 * <p>A plain wall-period {@link TickerBehaviour}: the period is only how often the conversation
 * clock is LOOKED AT (it is never a time source — the clock is driven by the game's advancer),
 * so it must not scale with the game pace the way a sim ticker does.
 */
public final class TimeoutWithdrawalBehaviour extends TickerBehaviour {

    private static final Logger log = LoggerFactory.getLogger(TimeoutWithdrawalBehaviour.class);
    /** How often the conversation clock is polled: a quarter of the counter's one-second step. */
    static final long POLL_WALL_MILLIS = 250L;

    private final AtomicReference<ConversationClock.Conversation> conversationRef;
    private final AtomicReference<WalkInState> stateRef;
    private final String conversationId;
    private final AtomicBoolean concluded;

    public TimeoutWithdrawalBehaviour(Agent agent,
                                      AtomicReference<ConversationClock.Conversation> conversationRef,
                                      AtomicReference<WalkInState> stateRef,
                                      String conversationId, AtomicBoolean concluded) {
        super(agent, POLL_WALL_MILLIS);
        this.conversationRef = conversationRef;
        this.stateRef = stateRef;
        this.conversationId = conversationId;
        this.concluded = concluded;
    }

    @Override
    protected void onTick() {
        ConversationClock.Conversation conversation = conversationRef.get();
        if (concluded.get()) {
            if (conversation != null) {
                conversation.close(); // backstop: every terminal path closes it, this makes sure
            }
            myAgent.removeBehaviour(this);
            return;
        }
        if (conversation == null) {
            return; // negotiation not opened yet
        }
        Optional<ConversationClock.View> view = conversation.view();
        if (view.isEmpty()) {
            // Swept by a world swap (load / new game): this vessel belongs to a world that is
            // gone and is about to be killed. It must not withdraw into the next one.
            myAgent.removeBehaviour(this);
            return;
        }
        if (view.get().expired()) {
            concluded.set(true);
            conversation.close();
            // DEBUG since task 26: ForwardWalkInToPlayerBehaviour logs this same withdrawal at the
            // hub, which is where an operator looks. Vessel-side detail stays in the file log.
            log.debug("{}: negotiation timed out ({} s of conversation time) -> withdraw",
                    myAgent.getLocalName(), view.get().elapsedMillis() / 1000L);
            myAgent.addBehaviour(new WithdrawalBehaviour(myAgent, stateRef, conversationId, "timeout"));
            myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
        }
    }
}
