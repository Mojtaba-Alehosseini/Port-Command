package it.unige.portcommand.behaviours.coordination;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The customs FAST path (S5, audit P2-4 — a task-05 no-op stub until now): an immediate
 * clearance CONFIRM for cargo the rules did not send to inspection. The rationale string names
 * the rules that decided that — {@code no_inspection_required [R22,R23]} for clean cargo whose
 * R23 roll missed, or {@code pre_cleared [R22,R24]} for a hypothetical future hazmat class the
 * R24 risk gate waves through — and rides the CONFIRM's {@code reason} field, which the
 * Paraphraser renders into the comm log.
 *
 * <p>The reply MUST be built with {@link MessageFactory#reply} — a fresh {@code create()} would
 * drop the customs protocol tag, miss {@code HandleEmergencyBehaviour}'s template, feed the
 * HarbourMaster's catch-all, and silently lose the {@code CustomsClearedEvent} and its €100.
 */
public final class PreClearBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(PreClearBehaviour.class);

    private final ACLMessage original;
    private final AtomicInteger refSeq;
    private final int clearanceYear;
    private final String rationale;

    public PreClearBehaviour(Agent agent, ACLMessage original, AtomicInteger refSeq,
                             int clearanceYear, String rationale) {
        super(agent);
        this.original = original;
        this.refSeq = refSeq;
        this.clearanceYear = clearanceYear;
        this.rationale = rationale;
    }

    @Override
    public void action() {
        ACLMessage reply = MessageFactory.reply(original, ACLMessage.CONFIRM);
        reply.setContent(TerminalJson.write(Map.of(
                "ref", "CL-" + clearanceYear + "-" + refSeq.getAndIncrement(),
                "reason", rationale)));
        myAgent.send(reply);
        log.info("CONFIRM clearance ({}) -> {}", rationale, original.getSender().getLocalName());
    }
}
