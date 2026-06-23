package it.unige.portcommand.behaviours.negotiation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.agents.BaseVesselAgent;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.negotiation.Decision;
import it.unige.portcommand.negotiation.NegotiationEngine;
import it.unige.portcommand.negotiation.WalkInState;
import it.unige.portcommand.ontology.VesselSpec;
import it.unige.portcommand.prolog.PrologQueries;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The walk-in negotiation core. On the player's counter (a PROPOSE relayed by the HM)
 * it FIRST runs the {@code isCompatible} guard (REFUSE on fail, before the engine is
 * ever consulted), then calls {@link NegotiationEngine#evaluate} and dispatches per
 * {@link Decision.Type}: ACCEPT→ACCEPT_PROPOSAL (+close), COUNTER→a fresh PROPOSE,
 * WITHDRAW→{@link WithdrawalBehaviour}. Also folds in the old CounterOffer /
 * AcceptOrReject stub behaviours (task-07 §64).
 */
public final class EvaluateCounterOfferBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(EvaluateCounterOfferBehaviour.class);

    private final AtomicReference<WalkInState> stateRef;
    private final NegotiationEngine engine;
    private final String conversationId;
    private final AtomicBoolean concluded;
    private final MessageTemplate template;

    public EvaluateCounterOfferBehaviour(Agent agent, AtomicReference<WalkInState> stateRef,
                                         NegotiationEngine engine, String conversationId,
                                         AtomicBoolean concluded) {
        super(agent);
        this.stateRef = stateRef;
        this.engine = engine;
        this.conversationId = conversationId;
        this.concluded = concluded;
        this.template = MessageTemplate.and(
                MessageTemplate.MatchConversationId(conversationId),
                MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                        MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL)));
    }

    @Override
    public void action() {
        if (concluded.get()) {
            myAgent.removeBehaviour(this);
            return;
        }
        ACLMessage msg = myAgent.receive(template);
        if (msg == null) {
            block();
            return;
        }
        BaseVesselAgent vessel = (BaseVesselAgent) myAgent;

        // Player accepted OUR standing PROPOSE -> close at our last offer.
        if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
            WalkInState s = stateRef.get();
            closeDeal(vessel, msg, "", s.lastOwnOffer(), s.dealHours()); // berth granted later (task 11)
            return;
        }

        // Player's counter (a PROPOSE).
        JsonNode counter = TerminalJson.readTreeOrNull(msg.getContent());
        double price = num(counter, "price");
        int hours = (int) num(counter, "hours");
        String berthId = text(counter, "berth_id");

        VesselSpec spec = vessel.spec();
        if (berthId == null || !PrologQueries.isCompatible(berthId, spec.vesselType(),
                spec.draft(), spec.length(), spec.tonnage())) {
            ACLMessage refuse = MessageFactory.reply(msg, ACLMessage.REFUSE);
            refuse.setContent(TerminalJson.write(Map.of("intent", "refuse", "reason", "berth_incompatible")));
            myAgent.send(refuse);
            log.info("{}: REFUSE counter (berth {} incompatible)", myAgent.getLocalName(), berthId);
            return;
        }

        WalkInState state = stateRef.get().recordPlayerCounter(price);
        stateRef.set(state);
        Decision decision = engine.evaluate(price, state);
        switch (decision.type()) {
            case ACCEPT -> {
                ACLMessage accept = MessageFactory.reply(msg, ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent(TerminalJson.write(Map.of("intent", "accept", "price", price, "hours", hours)));
                myAgent.send(accept);
                closeDeal(vessel, msg, berthId, price, hours);
            }
            case COUNTER -> {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("intent", "counter_offer");
                content.put("price", decision.newCounter());
                content.put("hours", hours);
                content.put("vessel_spec", spec);
                ACLMessage propose = MessageFactory.reply(msg, ACLMessage.PROPOSE);
                propose.setContent(TerminalJson.write(content));
                myAgent.send(propose);
                stateRef.set(state.recordOwnOffer(decision.newCounter()));
                log.info("{}: COUNTER at {} (round {})", myAgent.getLocalName(), decision.newCounter(), state.round());
            }
            case WITHDRAW -> {
                // Task 07 maps every engine WITHDRAW to "player_refused". TODO(task 15): when the real
                // engine distinguishes a price-exhaustion withdrawal, pass "over_priced" here so the
                // canonical WITHDRAW_PRICE outcome fires (the outcomeFor mapping already supports it).
                concluded.set(true);
                myAgent.addBehaviour(new WithdrawalBehaviour(myAgent, stateRef, conversationId, "player_refused"));
                myAgent.removeBehaviour(this);
            }
        }
    }

    private void closeDeal(BaseVesselAgent vessel, ACLMessage trigger, String berthId, double price, int hours) {
        vessel.setDeal(berthId, price, hours);
        concluded.set(true);
        log.info("{}: ACCEPT at {} ({}h) -> closing deal", myAgent.getLocalName(), price, hours);
        myAgent.addBehaviour(new DealClosedBehaviour(myAgent, conversationId));
        myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }

    private static double num(JsonNode node, String field) {
        if (node == null) {
            return 0.0;
        }
        JsonNode v = node.get(field);
        return v == null ? 0.0 : v.asDouble();
    }
}
