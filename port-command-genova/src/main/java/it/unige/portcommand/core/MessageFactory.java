package it.unige.portcommand.core;

import jade.lang.acl.ACLMessage;

/**
 * The single factory for every {@link ACLMessage} in the system. No agent or
 * behaviour constructs a raw {@code ACLMessage} — they all go through
 * {@link #create(int)} so the protocol envelope (ontology + content language) is
 * set uniformly. Enforced by a grep gate in CI/review.
 */
public final class MessageFactory {

    /** The single ontology name carried by every message in this project. */
    public static final String ONTOLOGY = "port_command_v1";
    /** The single content language; payloads are JSON. */
    public static final String LANGUAGE = "json";

    private MessageFactory() {
    }

    /**
     * Builds an {@link ACLMessage} with the given performative and the standard
     * {@code port_command_v1} / {@code json} envelope.
     *
     * @param performative a {@code jade.lang.acl.ACLMessage} performative constant
     * @return a new message ready for the caller to set receiver + content
     */
    public static ACLMessage create(int performative) {
        ACLMessage message = new ACLMessage(performative);
        message.setOntology(ONTOLOGY);
        message.setLanguage(LANGUAGE);
        return message;
    }

    /**
     * Builds a reply to {@code original} with the given performative. Uses
     * {@link ACLMessage#createReply()} so the conversation envelope
     * (receiver=sender, conversation-id, in-reply-to, protocol) is preserved, then
     * re-stamps {@link #ONTOLOGY}/{@link #LANGUAGE} so the reply always carries the
     * protocol envelope regardless of what the original set. The single sanctioned
     * way to construct a reply — no agent calls {@code createReply()} directly.
     */
    public static ACLMessage reply(ACLMessage original, int performative) {
        ACLMessage r = original.createReply();
        r.setPerformative(performative);
        r.setOntology(ONTOLOGY);
        r.setLanguage(LANGUAGE);
        return r;
    }
}
