package it.unige.portcommand.behaviours.auto_flow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contracted vessel's arrival announcement: a REQUEST to the HarbourMaster asking for
 * its berth, carrying the contract id and the full {@link it.unige.portcommand.ontology.VesselSpec}.
 * Then it attaches {@link AwaitBerthBehaviour} on the same conversation id.
 */
public final class AnnounceArrivalBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(AnnounceArrivalBehaviour.class);

    /** Mirrors AutoFlowDispatcherBehaviour's DF-retry pair (S10, hunter B finding 7): the
     * delay must exceed ServiceLocator's 1 s cache TTL — it caches even an EMPTY result, so
     * a faster retry would just re-read the failure. */
    private static final int DF_RETRY_ATTEMPTS = 3;
    private static final long DF_RETRY_WALL_MS = 1200L;

    private final String contractId;
    private final String conversationId;
    private final int attemptsLeft;

    public AnnounceArrivalBehaviour(Agent agent, String contractId) {
        this(agent, contractId, DF_RETRY_ATTEMPTS);
    }

    private AnnounceArrivalBehaviour(Agent agent, String contractId, int attemptsLeft) {
        super(agent);
        this.contractId = contractId;
        this.conversationId = "berth-" + agent.getLocalName();
        this.attemptsLeft = attemptsLeft;
    }

    @Override
    public void action() {
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        Optional<AID> harbourMaster = vessel.serviceLocator().findUnique("harbour-master");
        if (harbourMaster.isEmpty()) {
            // S10 (bug hunt, hunter B finding 7): one transient DF hiccup here used to orphan
            // the vessel FOREVER — no announce, no phase, no berth listener, no retry, and the
            // HarbourMaster never learns it exists. The same lookup in AutoFlowDispatcher
            // already retried; this one just hadn't. Bounded retry, then a loud terminal.
            if (attemptsLeft > 1) {
                log.warn("{}: no harbour-master in DF — retrying announce in {} ms ({} left)",
                        vessel.getLocalName(), DF_RETRY_WALL_MS, attemptsLeft - 1);
                vessel.addBehaviour(new jade.core.behaviours.WakerBehaviour(vessel, DF_RETRY_WALL_MS) {
                    @Override
                    protected void onWake() {
                        vessel.addBehaviour(new AnnounceArrivalBehaviour(vessel, contractId, attemptsLeft - 1));
                    }
                });
            } else {
                log.error("{}: no harbour-master in DF after {} attempts — arrival never "
                        + "announced, vessel is stranded (unreachable in a healthy container)",
                        vessel.getLocalName(), DF_RETRY_ATTEMPTS);
            }
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("intent", "request_berth");
        content.put("contract", contractId == null ? "" : contractId);
        content.put("vessel_spec", vessel.spec());

        ACLMessage request = MessageFactory.create(ACLMessage.REQUEST);
        request.addReceiver(harbourMaster.get());
        request.setConversationId(conversationId);
        request.setContent(TerminalJson.write(content));
        myAgent.send(request);

        // Phase AWAITING_BERTH (S5, ADR-16): same reasoning as the walk-in side — the
        // announce-to-grant window becomes a resumable phase instead of a null that transiently
        // refused manual saves ("between phases"), and the contracted queue (a berth-sharing
        // future contract) parks here safely.
        vessel.reportPhase(BaseVesselAgent.FlowPhase.AWAITING_BERTH, 0L);
        myAgent.addBehaviour(new AwaitBerthBehaviour(myAgent, conversationId));
        log.info("{} announced arrival (contract {}) -> harbour-master", myAgent.getLocalName(), contractId);
    }
}
