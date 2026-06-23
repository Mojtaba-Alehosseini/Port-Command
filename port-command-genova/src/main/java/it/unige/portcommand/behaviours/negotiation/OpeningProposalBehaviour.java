package it.unige.portcommand.behaviours.negotiation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.negotiation.WalkInState;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walk-in opening move: PROPOSE an opening price ({@code targetPrice * personality
 * opening modifier}) and the desired duration to the HarbourMaster, and stamp the
 * negotiation start time. Only price / hours / vessel_spec go on the wire — the hidden
 * beliefs stay in {@link WalkInState}.
 */
public final class OpeningProposalBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(OpeningProposalBehaviour.class);

    private final AtomicReference<WalkInState> stateRef;
    private final String conversationId;
    private final AtomicBoolean concluded;

    public OpeningProposalBehaviour(Agent agent, AtomicReference<WalkInState> stateRef,
                                    String conversationId, AtomicBoolean concluded) {
        super(agent);
        this.stateRef = stateRef;
        this.conversationId = conversationId;
        this.concluded = concluded;
    }

    @Override
    public void action() {
        if (concluded.get()) {
            return;
        }
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;
        WalkInState state = stateRef.get();
        double opening = state.targetPrice() * state.personality().openingModifier();

        Optional<AID> harbourMaster = vessel.serviceLocator().findUnique("harbour-master");
        if (harbourMaster.isEmpty()) {
            log.warn("{}: no harbour-master in DF; cannot open negotiation", myAgent.getLocalName());
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("intent", "opening_offer");
        content.put("price", opening);
        content.put("hours", state.dealHours());
        content.put("vessel_spec", vessel.spec());

        ACLMessage propose = MessageFactory.create(ACLMessage.PROPOSE);
        propose.addReceiver(harbourMaster.get());
        propose.setConversationId(conversationId);
        propose.setContent(TerminalJson.write(content));
        myAgent.send(propose);

        stateRef.set(state.markStarted(vessel.simClock().nowSimMillis()).recordOwnOffer(opening));
        log.info("{} opened at {} ({}h) -> harbour-master", myAgent.getLocalName(), opening, state.dealHours());
    }
}
