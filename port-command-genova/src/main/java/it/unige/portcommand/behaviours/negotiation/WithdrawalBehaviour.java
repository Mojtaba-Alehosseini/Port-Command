package it.unige.portcommand.behaviours.negotiation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.artifacts.DealRecord;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.negotiation.WalkInState;
import it.unige.portcommand.ontology.Deal;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ends a walk-in negotiation: INFORM the HarbourMaster of the withdrawal, record the
 * outcome in {@link it.unige.portcommand.artifacts.MarketHistoryArtifact} with the
 * canonical {@link Deal.Outcome} for the reason, then {@code doDelete()}. Fired from the
 * counter-eval (the engine withdraws with {@code over_priced}, or — task 19b —
 * {@code too_short} when the fee was affordable at the final call but the player never
 * granted the cargo-handling floor), the player-refuse path ({@code player_refused}),
 * and the timeout ticker ({@code timeout}).
 */
public final class WithdrawalBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalBehaviour.class);

    private final AtomicReference<WalkInState> stateRef;
    private final String conversationId;
    private final String reason;

    public WithdrawalBehaviour(Agent agent, AtomicReference<WalkInState> stateRef,
                               String conversationId, String reason) {
        super(agent);
        this.stateRef = stateRef;
        this.conversationId = conversationId;
        this.reason = reason;
    }

    @Override
    public void action() {
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        WalkInState state = stateRef.get();
        // Task 24 (checkpoint #5, Moji's balance call): did the player ever counter in this
        // dialogue? round() starts at 1 and increments only in WalkInState.recordPlayerCounter
        // (called synchronously when a player counter is processed, before any vessel response),
        // so round() > 1 is a race-free "player engaged" signal. It rides the withdraw INFORM so
        // the HM can split the TIMEOUT reputation penalty (−1 never-engaged vs −3 abandoned).
        boolean playerEngaged = state != null && state.round() > 1;
        Optional<AID> hm = vessel.serviceLocator().findUnique("harbour-master");
        if (hm.isPresent()) {
            ACLMessage inform = MessageFactory.create(ACLMessage.INFORM);
            inform.addReceiver(hm.get());
            inform.setConversationId(conversationId);
            inform.setContent(TerminalJson.write(Map.of(
                    "intent", "withdraw", "reason", reason, "engaged", playerEngaged)));
            myAgent.send(inform);
        }
        double lastPrice = state == null ? 0.0 : state.lastOwnOffer();
        int hours = state == null ? 0 : state.lastOwnHours(); // the standing hours term (19b), = dealHours pre-counter
        vessel.marketHistory().record(new DealRecord(
                vessel.spec().vesselType(), hours, lastPrice,
                vessel.simClock().nowSimMillis(), outcomeFor(reason)));
        // DEBUG since task 26 - see TimeoutWithdrawalBehaviour: one event, one INFO line, at the hub.
        log.debug("{}: withdrew ({}) -> INFORM + recorded + doDelete", myAgent.getLocalName(), reason);
        myAgent.doDelete();
    }

    /** Maps a withdrawal reason to its canonical {@link Deal.Outcome} (task-07 §164;
     * {@code too_short} added by task 19b for the duration dimension's terminal case). */
    public static Deal.Outcome outcomeFor(String reason) {
        return switch (reason) {
            case "over_priced" -> Deal.Outcome.WITHDRAW_PRICE;
            case "too_short" -> Deal.Outcome.WITHDRAW_DURATION;
            case "timeout" -> Deal.Outcome.TIMEOUT;
            case "player_refused" -> Deal.Outcome.PLAYER_REFUSED;
            default -> throw new IllegalArgumentException("unknown withdrawal reason: " + reason);
        };
    }
}
