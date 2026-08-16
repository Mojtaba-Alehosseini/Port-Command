package it.unige.portcommand.behaviours.coordination;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.prolog.PrologQueries;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Customs-side ROUTER for clearance REQUESTs (S5 restructure, audit P2-4). The rules decide,
 * the two task-05 stubs execute: R22 {@code needs_inspection} gates the hazmat leg, R21
 * {@code clearance_ok} the blacklist (INFORM flagged, priority=high), R24 {@code high_risk}
 * sends hazmat to {@link FlagForInspectionBehaviour} (a sim-derived-delay CONFIRM), and clean
 * cargo keeps R23's seeded probability roll exactly as before — a miss pre-clears through
 * {@link PreClearBehaviour}, a hit inspects. Every CONFIRM carries a rules-cited rationale the
 * Paraphraser renders. All replies carry the envelope via {@link MessageFactory#reply}.
 */
public final class HandleClearanceRequestBehaviour extends CyclicBehaviour {

    private static final Logger log = LoggerFactory.getLogger(HandleClearanceRequestBehaviour.class);
    private static final int CLEARANCE_YEAR = 2026; // fixed (no wall clock); scenarios may override later
    public static final MessageTemplate TEMPLATE = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
            MessageTemplate.MatchOntology(MessageFactory.ONTOLOGY));

    private final Random customsRng;
    private final AtomicInteger refSeq;
    private final it.unige.portcommand.util.SimClock simClock;

    public HandleClearanceRequestBehaviour(Agent agent, Random customsRng, AtomicInteger refSeq,
                                           it.unige.portcommand.util.SimClock simClock) {
        super(agent);
        this.customsRng = customsRng;
        this.refSeq = refSeq;
        this.simClock = simClock;
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(TEMPLATE);
        if (msg == null) {
            block();
            return;
        }
        JsonNode req = TerminalJson.readTreeOrNull(msg.getContent());
        String vesselType = text(req, "vessel_type");
        String cargoClass = text(req, "cargo_class");
        if (vesselType == null || cargoClass == null) {
            flag(msg, "malformed");
            return;
        }
        log.info("clearance REQUEST {} / {} from {}", vesselType, cargoClass, msg.getSender().getLocalName());

        // S5 (audit P2-4): the ROUTER over the customs rules — R22 decides whether inspection is
        // in play at all, R21 the blacklist, R24 the risk gate, R23 the clean-cargo roll. The two
        // task-05 stubs are the executors: PreClearBehaviour (immediate CONFIRM) and
        // FlagForInspectionBehaviour (delayed CONFIRM). Every rationale names its rules and is
        // rendered into the comm log by the Paraphraser.
        if (PrologQueries.needsInspection(cargoClass)) {                    // R22
            if (!PrologQueries.clearanceOk(vesselType, cargoClass)) {       // R21 (blacklist)
                flag(msg, "blacklisted [R21,R22]");
            } else if (PrologQueries.highRisk(vesselType, cargoClass)) {    // R24
                myAgent.addBehaviour(new FlagForInspectionBehaviour(myAgent, msg, refSeq,
                        CLEARANCE_YEAR, "inspected [R22,R24]", simClock));
            } else {
                // Unreachable with today's ontology (hazmat is always high-risk under R23's 0.8)
                // but the honest future-proof branch: a low-risk hazmat class would pre-clear.
                myAgent.addBehaviour(new PreClearBehaviour(myAgent, msg, refSeq,
                        CLEARANCE_YEAR, "pre_cleared [R22,R24]"));
            }
        } else if (!PrologQueries.clearanceOk(vesselType, cargoClass)) {
            // R21 on the clean leg too (S5 adversarial finding 2): clearance_ok validates BOTH
            // atoms besides the blacklist, and D7 made this arm reachable by every contracted
            // vessel — an unrecognised cargo class (a hand-edited save survives
            // SaveLoadManager.validateState, which checks type/phase/stage but not cargoClass)
            // used to fall through to inspectionProbability's orElseThrow and KILL the agent's
            // only clearance behaviour. Flag instead; the roll below stays unconsumed.
            flag(msg, "clearance_refused [R21]");
        } else {
            // Clean cargo: R23's probability roll — same seeded stream, same within-branch
            // order as pre-S5. NOT scenario-identical though (adversarial finding 8): D7 sends
            // every contracted arrival here, so the stream gains consumers it never had, and
            // the calibration model moved accordingly (10,425 → 10,225). A hit is now a
            // delayed CONFIRM through the inspection stub rather than the retired
            // routine_inspection INFORM (which nothing HM-side ever consumed).
            double probability = PrologQueries.inspectionProbability(vesselType, cargoClass);
            if (customsRng.nextDouble() < probability) {
                myAgent.addBehaviour(new FlagForInspectionBehaviour(myAgent, msg, refSeq,
                        CLEARANCE_YEAR, "inspected [R23]", simClock));
            } else {
                myAgent.addBehaviour(new PreClearBehaviour(myAgent, msg, refSeq,
                        CLEARANCE_YEAR, "no_inspection_required [R22,R23]"));
            }
        }
    }


    private void flag(ACLMessage original, String reason) {
        ACLMessage reply = MessageFactory.reply(original, ACLMessage.INFORM);
        reply.setContent(TerminalJson.write(Map.of("event", "flagged", "reason", reason)));
        reply.addUserDefinedParameter("priority", "high");
        myAgent.send(reply);
        log.info("INFORM flagged ({}) -> {}", reason, original.getSender().getLocalName());
    }


    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null ? null : value.asText();
    }
}
