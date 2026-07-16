package it.unige.portcommand.nlp;

import java.util.List;
import java.util.Objects;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

/**
 * Maps a DCG-parsed {@link Frame} to an {@link ACLMessage} — the frame&rarr;ACL switch of
 * PROJECT_DEFINITION.md §6.1, over the five DCG move types of §6.2. Content is the JSON of
 * {@link Frame#elements()}. Built exclusively via {@link MessageFactory} — no raw
 * {@code new ACLMessage(...)}.
 *
 * <p><b>Ownership note (2026-07-16, task 16).</b> Created by task 14 against the stub
 * {@code Frame}, whose javadoc flagged the switch as provisional ("the frame vocabulary fills
 * in when task 16 completes"). It has now filled in: the real grammar emits ONE frame name for
 * all five negotiation moves ({@code commerce_sell} — the FrameNet frame is a property of the
 * negotiation, not of the move), and carries the move in the {@code move} element. So
 * {@link #performativeFor} switches on {@code frame.move()} where it used to switch on
 * {@code frame.frameName()}. See ADR-10.
 */
public final class FrameToAcl {

    private FrameToAcl() {
    }

    /**
     * @param receiver       the AID to address the message to, or {@code null} to leave the
     *                       receiver unset (the caller, which owns the active per-vessel
     *                       dialogue mapping, addresses it before {@code send()})
     * @param conversationId the FIPA conversation id, or {@code null} to leave it unset
     */
    public static ACLMessage build(Frame frame, AID receiver, String conversationId) {
        Objects.requireNonNull(frame, "frame");
        ACLMessage message = MessageFactory.create(performativeFor(frame));
        if (receiver != null) {
            message.addReceiver(receiver);
        }
        if (conversationId != null) {
            message.setConversationId(conversationId);
        }
        message.setContent(TerminalJson.write(frame.elements()));
        return message;
    }

    /**
     * Fans a single (typically 16-M2 {@code command}) frame out to N receivers — one independent
     * {@link MessageFactory}-built envelope each, so "hold all tankers" / "send two tugs" reaches
     * every resolved agent. The caller resolves the frame's quantified patient
     * ({@code quantifier}/{@code patient}) to the receiver AIDs; this method only fans out.
     *
     * @return one message per receiver, in receiver order (empty list for no receivers)
     */
    public static List<ACLMessage> buildAll(Frame frame, List<AID> receivers, String conversationId) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(receivers, "receivers");
        return receivers.stream().map(r -> build(frame, r, conversationId)).toList();
    }

    /**
     * {@code propose}/{@code counter} -&gt; PROPOSE (a counter-offer is a fresh proposal in FIPA
     * terms — see {@code negotiation.Decision}'s COUNTER javadoc for the same rule),
     * {@code accept} -&gt; ACCEPT_PROPOSAL, {@code reject} -&gt; REJECT_PROPOSAL, {@code ask}
     * -&gt; QUERY_REF.
     *
     * <p>The frame NAME is only consulted to separate the two frames the grammar can emit: the
     * negotiation frame ({@code commerce_sell}, which carries a {@code move}) and 16-M2's
     * imperative frame ({@code command}, which carries none — an imperative maps to REQUEST,
     * the FIPA performative for "I want you to do this").
     */
    private static int performativeFor(Frame frame) {
        String frameName = frame.frameName();
        if (frameName == null) {
            throw new IllegalArgumentException("frame.frameName() must not be null");
        }
        if ("command".equals(frameName)) {
            return ACLMessage.REQUEST;
        }
        String move = frame.move();
        if (move == null) {
            throw new IllegalArgumentException(
                    "frame '" + frameName + "' carries no 'move' element: " + frame.elements());
        }
        return switch (move) {
            case "propose", "counter" -> ACLMessage.PROPOSE;
            case "accept" -> ACLMessage.ACCEPT_PROPOSAL;
            case "reject" -> ACLMessage.REJECT_PROPOSAL;
            case "ask" -> ACLMessage.QUERY_REF;
            // 16-M2 negation block: "nothing below 2000" sets a constraint on the negotiation —
            // REQUEST, matching Rasa's set_constraint routing (NLPPipeline.routeHighConfidence).
            case "constrain" -> ACLMessage.REQUEST;
            default -> throw new IllegalArgumentException("unknown DCG move type: " + move);
        };
    }
}
