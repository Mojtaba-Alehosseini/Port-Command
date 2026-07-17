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
 *
 * <p><b>Task 19 play-test fix:</b> also matches the player's REJECT_PROPOSAL ("reject"
 * in chat) and CANCEL ("withdraw") — both end the negotiation with the canonical
 * {@code player_refused} outcome. Before this, neither performative matched ANY
 * vessel-side template: the player's refusal was silently ignored, the dialogue stayed
 * open, and a later "deal" could still close it — and the {@code PLAYER_REFUSED}
 * outcome (declared since task 02, mapped since task 07) was unreachable in production.
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
                        MessageTemplate.or(
                                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                                MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL)),
                        MessageTemplate.or(
                                MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL),
                                MessageTemplate.MatchPerformative(ACLMessage.CANCEL))));
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

        // Player accepted OUR standing PROPOSE -> close at our last offer: the standing price
        // AND the standing hours term (task 19b — after an hours counter, "deal" closes at the
        // countered hours, not the original preferred stay).
        if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
            WalkInState s = stateRef.get();
            closeDeal(vessel, msg, "", s.lastOwnOffer(), s.lastOwnHours()); // berth granted later (task 11)
            return;
        }

        // Player refused ("reject") or withdrew ("withdraw") -> leave with the canonical
        // player_refused outcome (task 19; see class javadoc).
        if (msg.getPerformative() == ACLMessage.REJECT_PROPOSAL || msg.getPerformative() == ACLMessage.CANCEL) {
            concluded.set(true);
            log.info("{}: player refused/withdrew -> leaving", myAgent.getLocalName());
            myAgent.addBehaviour(new WithdrawalBehaviour(myAgent, stateRef, conversationId, "player_refused"));
            myAgent.removeBehaviour(this); // MUST be last: removeBehaviour nulls myAgent
            return;
        }

        // Player's counter (a PROPOSE). Task 19b: §7.3 negotiates price AND duration, so a
        // PRESENT parsed duration flows to the engine as a real term. An ABSENT "hours" key
        // resolves to the vessel's standing hours term (initially its preferred stay) — NEVER
        // to the JSON-decoder's 0.0 default: that absent-key->0 coercion was exactly task 19's
        // "deal closes at 0 hours" bug, which task 19 fixed by ignoring the frame's duration
        // entirely and 19b re-opens deliberately. A present-but-degenerate value (0/negative)
        // is left to the engine, whose floor branch counters it up — templates pin every floor
        // >= 1, so a degenerate proposal can never survive into a deal either way.
        JsonNode counter = TerminalJson.readTreeOrNull(msg.getContent());
        Double parsedPrice = numOrNull(counter, "price");
        Integer proposedHours = intOrNull(counter, "hours");
        int hours = proposedHours != null ? proposedHours : stateRef.get().lastOwnHours();
        String berthId = text(counter, "berth_id");

        // Price guard (post-19b hardening; adversarial review observation). Unlike an absent
        // DURATION — a legitimate conversational move, resolved to the standing hours above —
        // an absent/non-positive PRICE is producer garbage: the DCG fails a price-less counter
        // at parse time ("a counter with no price is meaningless"), so nothing legitimate emits
        // one. Under buyer semantics a defaulted 0.0 would satisfy price <= lastOwnOffer and
        // close a deal at €0 the player never uttered. No binding action from malformed input:
        // REFUSE loudly (same precedent as the berth guard below), don't consult the engine,
        // don't burn a round.
        if (parsedPrice == null || parsedPrice <= 0) {
            ACLMessage refuse = MessageFactory.reply(msg, ACLMessage.REFUSE);
            refuse.setContent(TerminalJson.write(Map.of("intent", "refuse", "reason", "invalid_price")));
            myAgent.send(refuse);
            log.warn("{}: REFUSE counter (absent/non-positive price in {})", myAgent.getLocalName(), msg.getContent());
            return;
        }
        double price = parsedPrice;

        VesselSpec spec = vessel.spec();
        if (berthId == null || !PrologQueries.isCompatible(berthId, spec.vesselType(),
                spec.draft(), spec.length(), spec.tonnage())) {
            ACLMessage refuse = MessageFactory.reply(msg, ACLMessage.REFUSE);
            refuse.setContent(TerminalJson.write(Map.of("intent", "refuse", "reason", "berth_incompatible")));
            myAgent.send(refuse);
            log.info("{}: REFUSE counter (berth {} incompatible)", myAgent.getLocalName(), berthId);
            return;
        }

        WalkInState state = stateRef.get().recordPlayerCounter(price, hours);
        stateRef.set(state);
        Decision decision = engine.evaluate(price, hours, state);
        switch (decision.type()) {
            case ACCEPT -> {
                ACLMessage accept = MessageFactory.reply(msg, ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent(TerminalJson.write(
                        Map.of("intent", "accept", "price", price, "hours", decision.newHours())));
                myAgent.send(accept);
                closeDeal(vessel, msg, berthId, price, decision.newHours());
            }
            case COUNTER -> {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("intent", "counter_offer");
                content.put("price", decision.newCounter());
                content.put("hours", decision.newHours());
                content.put("vessel_spec", spec);
                ACLMessage propose = MessageFactory.reply(msg, ACLMessage.PROPOSE);
                propose.setContent(TerminalJson.write(content));
                myAgent.send(propose);
                stateRef.set(state.recordOwnOffer(decision.newCounter(), decision.newHours()));
                log.info("{}: COUNTER at {} for {}h (round {}, {})", myAgent.getLocalName(),
                        decision.newCounter(), decision.newHours(), state.round(), decision.reason());
            }
            case WITHDRAW -> {
                // The engine only ever withdraws at rounds-exhausted, for the dimension that
                // killed the deal (task 15's outcome-ownership contract, widened by 19b:
                // evaluate() produces WITHDRAW("over_priced") or WITHDRAW("too_short") only;
                // TIMEOUT and PLAYER_REFUSED come from TimeoutWithdrawalBehaviour and the
                // explicit player-refuse path respectively, never from here) -- pass the
                // engine's own reason straight through so WithdrawalBehaviour.outcomeFor
                // records the canonical WITHDRAW_PRICE / WITHDRAW_DURATION.
                concluded.set(true);
                myAgent.addBehaviour(new WithdrawalBehaviour(myAgent, stateRef, conversationId, decision.reason()));
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

    /** Absent key -> {@code null} (so the caller can fall back to the standing hours), never a
     * silent 0 — the 0-default coercion is what made task 19's "deal at 0 hours" bug. */
    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : (int) v.asDouble();
    }

    /** Absent key -> {@code null} (so the price guard can REFUSE instead of reading a silent
     * 0.0 — the same coercion class as the hours bug, on the dimension where 0 would CLOSE
     * a €0 deal under buyer semantics rather than get floor-countered). */
    private static Double numOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asDouble();
    }
}
