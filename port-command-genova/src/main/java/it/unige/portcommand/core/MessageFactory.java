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
}
