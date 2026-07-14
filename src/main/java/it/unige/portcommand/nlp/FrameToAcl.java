package it.unige.portcommand.nlp;

import java.util.Objects;

import it.unige.portcommand.core.MessageFactory;
import it.unige.portcommand.core.TerminalJson;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

/**
 * Maps a DCG-parsed {@link Frame} to an {@link ACLMessage} — the frame&rarr;ACL switch of
 * PROJECT_DEFINITION.md §6.1, over the five DCG move types of §6.2. Content is the JSON of
 * {@link Frame#elements()}. Owned by this task because it depends only on the stub {@code
 * Frame} shape; the frame vocabulary fills in when task 16 completes. Built exclusively via
 * {@link MessageFactory} — no raw {@code new ACLMessage(...)}.
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
        ACLMessage message = MessageFactory.create(performativeFor(frame.frameName()));
        if (receiver != null) {
            message.addReceiver(receiver);
        }
        if (conversationId != null) {
            message.setConversationId(conversationId);
        }
        message.setContent(TerminalJson.write(frame.elements()));
        return message;
    }

    /** {@code propose}/{@code counter} -&gt; PROPOSE (a counter-offer is a fresh proposal in
     * FIPA terms — see {@code negotiation.Decision}'s COUNTER javadoc for the same rule),
     * {@code accept} -&gt; ACCEPT_PROPOSAL, {@code reject} -&gt; REJECT_PROPOSAL, {@code ask}
     * -&gt; QUERY_REF. */
    private static int performativeFor(String moveType) {
        if (moveType == null) {
            throw new IllegalArgumentException("frame.frameName() must not be null");
        }
        return switch (moveType) {
            case "propose", "counter" -> ACLMessage.PROPOSE;
            case "accept" -> ACLMessage.ACCEPT_PROPOSAL;
            case "reject" -> ACLMessage.REJECT_PROPOSAL;
            case "ask" -> ACLMessage.QUERY_REF;
            default -> throw new IllegalArgumentException("unknown DCG move type: " + moveType);
        };
    }
}
