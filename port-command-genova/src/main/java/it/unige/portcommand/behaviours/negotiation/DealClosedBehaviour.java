package it.unige.portcommand.behaviours.negotiation;

import java.util.Map;
import java.util.Optional;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.artifacts.DealRecord;
import it.unige.portcommand.behaviours.auto_flow.AwaitBerthBehaviour;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.ontology.Deal;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On a closed walk-in deal: CONFIRM to the HarbourMaster, record the deal in the
 * {@link it.unige.portcommand.artifacts.MarketHistoryArtifact} as {@link Deal.Outcome#DEAL},
 * then become "contracted" by attaching {@link AwaitBerthBehaviour} (the HM grants the
 * berth in task 11). The negotiation behaviours have already removed themselves.
 */
public final class DealClosedBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(DealClosedBehaviour.class);

    /** Mirrors AutoFlowDispatcherBehaviour's DF-retry pair (S10, hunter B finding 7): the
     * delay must exceed ServiceLocator's 1 s cache TTL — it caches even an EMPTY result. */
    private static final int DF_RETRY_ATTEMPTS = 3;
    private static final long DF_RETRY_WALL_MS = 1200L;

    private final String conversationId;

    public DealClosedBehaviour(Agent agent, String conversationId) {
        super(agent);
        this.conversationId = conversationId;
    }

    @Override
    public void action() {
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        // Record BEFORE confirming so anyone observing the CONFIRM also sees the recorded deal.
        vessel.marketHistory().record(new DealRecord(
                vessel.spec().vesselType(), vessel.dealHours(), vessel.dealPrice(),
                vessel.simClock().nowSimMillis(), Deal.Outcome.DEAL));

        sendConfirm(vessel, DF_RETRY_ATTEMPTS);

        // Phase AWAITING_BERTH (S5, ADR-16): the deal-to-grant window is now a REAL resumable
        // phase, because the wait queue can stretch it to minutes. Before this, flowPhase stayed
        // null here, refusing every manual save and making the EOD autosave drop a vessel whose
        // fee was already booked. No deadline: the grant's timing belongs to the HarbourMaster.
        vessel.reportPhase(BaseVesselAgent.FlowPhase.AWAITING_BERTH, 0L);
        myAgent.addBehaviour(new AwaitBerthBehaviour(myAgent, "berth-" + myAgent.getLocalName()));
        log.info("{}: deal closed at {} ({}h) -> CONFIRM + recorded + awaiting berth",
                myAgent.getLocalName(), vessel.dealPrice(), vessel.dealHours());
    }

    /**
     * The CONFIRM half, with the bounded DF retry (S10, hunter B finding 7). Skipping it —
     * the pre-S10 behaviour on an empty lookup — left the HarbourMaster's tracking at
     * NEGOTIATING forever: the vessel then waited with no deadline ({@code concluded} already
     * true, the timeout behaviour long gone) and every MANUAL SAVE for the rest of the run
     * was refused with "finish the current negotiation with X". Uses the captured
     * {@code vessel} reference throughout — the outer OneShot's {@code myAgent} is nulled by
     * the scheduler before a waker can fire.
     */
    private void sendConfirm(BaseVesselAgent vessel, int attemptsLeft) {
        Optional<AID> hm = vessel.serviceLocator().findUnique("harbour-master");
        if (hm.isEmpty()) {
            if (attemptsLeft > 1) {
                log.warn("{}: no harbour-master in DF — retrying deal CONFIRM in {} ms ({} left)",
                        vessel.getLocalName(), DF_RETRY_WALL_MS, attemptsLeft - 1);
                vessel.addBehaviour(new jade.core.behaviours.WakerBehaviour(vessel, DF_RETRY_WALL_MS) {
                    @Override
                    protected void onWake() {
                        sendConfirm(vessel, attemptsLeft - 1);
                    }
                });
            } else {
                log.error("{}: deal_confirmed CONFIRM undeliverable after {} attempts — the "
                        + "HarbourMaster keeps this negotiation open and manual saves will be "
                        + "refused naming this vessel", vessel.getLocalName(), DF_RETRY_ATTEMPTS);
            }
            return;
        }
        ACLMessage confirm = MessageFactory.create(ACLMessage.CONFIRM);
        confirm.addReceiver(hm.get());
        confirm.setConversationId(conversationId);
        confirm.setContent(TerminalJson.write(Map.of(
                "intent", "deal_confirmed",
                "price", vessel.dealPrice(),
                "hours", vessel.dealHours())));
        vessel.send(confirm);
    }
}
