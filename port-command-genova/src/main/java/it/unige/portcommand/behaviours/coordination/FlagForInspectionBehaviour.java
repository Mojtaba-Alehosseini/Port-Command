package it.unige.portcommand.behaviours.coordination;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import it.unige.portcommand.util.SimClock;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The customs INSPECTION path (S5, audit P2-4 — a task-05 no-op stub until now): the cargo the
 * rules flagged for inspection gets its clearance CONFIRM only after an inspection delay. The
 * rationale names the deciding rules — {@code inspected [R22,R24]} for hazmat (R24's high-risk
 * gate), {@code inspected [R23]} for a clean-cargo random inspection (R23's probability roll) —
 * and rides the delayed CONFIRM's {@code reason} field into the comm log.
 *
 * <p><b>The delay is sim-derived, wall-clamped:</b>
 * {@code clamp(simSecondsToWallMs(120 sim-s), 250 ms, 5 s)}. Same reasoning as
 * {@code InitiateCNPBehaviour.MIN_CNP_WINDOW_WALL_MS}: the inspection is a demonstrability BEAT,
 * not a simulation quantity — at demo pacing (900 s/day) it is a visible ~1.25 s of
 * "inspecting…", at an IT's 86,400 s/day the ceiling keeps awaits green, at smoke's 5 s/day the
 * floor keeps it observable. The whole flow is parallel to the grant→CNP→transit pipeline, so
 * the storm's sub-second CANCEL margin is untouched — only the CONFIRM (and its €100 charge)
 * arrives later.
 *
 * <p>The reply is built with {@link MessageFactory#reply} inside the waker — see
 * {@code PreClearBehaviour}'s javadoc for why {@code create()} would lose the money.
 * The waker is ANONYMOUS: a named class in this catalogue-counted package would break the
 * locked 27, and {@code BehaviourCatalogueTest} skips {@code $} names.
 */
public final class FlagForInspectionBehaviour extends OneShotBehaviour {

    private static final Logger log = LoggerFactory.getLogger(FlagForInspectionBehaviour.class);

    /** The inspection's sim duration; see the class javadoc for the wall clamp. */
    static final long INSPECTION_SIM_SECONDS = 120L;
    static final long MIN_INSPECTION_WALL_MS = 250L;
    static final long MAX_INSPECTION_WALL_MS = 5_000L;

    private final ACLMessage original;
    private final AtomicInteger refSeq;
    private final int clearanceYear;
    private final String rationale;
    private final SimClock simClock;

    public FlagForInspectionBehaviour(Agent agent, ACLMessage original, AtomicInteger refSeq,
                                      int clearanceYear, String rationale, SimClock simClock) {
        super(agent);
        this.original = original;
        this.refSeq = refSeq;
        this.clearanceYear = clearanceYear;
        this.rationale = rationale;
        this.simClock = simClock;
    }

    @Override
    public void action() {
        long delayWallMs = Math.max(MIN_INSPECTION_WALL_MS,
                Math.min(MAX_INSPECTION_WALL_MS, simClock.simSecondsToWallMs(INSPECTION_SIM_SECONDS)));
        log.info("inspection started for {} ({}) — clearance in ~{} ms wall",
                original.getSender().getLocalName(), rationale, delayWallMs);
        myAgent.addBehaviour(new WakerBehaviour(myAgent, delayWallMs) {
            @Override
            protected void onWake() {
                ACLMessage reply = MessageFactory.reply(original, ACLMessage.CONFIRM);
                reply.setContent(TerminalJson.write(Map.of(
                        "ref", "CL-" + clearanceYear + "-" + refSeq.getAndIncrement(),
                        "reason", rationale,
                        "inspection_sim_seconds", INSPECTION_SIM_SECONDS)));
                myAgent.send(reply);
                log.info("CONFIRM clearance after inspection ({}) -> {}", rationale,
                        original.getSender().getLocalName());
            }
        });
    }
}
