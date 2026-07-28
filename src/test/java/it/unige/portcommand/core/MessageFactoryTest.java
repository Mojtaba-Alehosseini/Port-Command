package it.unige.portcommand.core;

import jade.core.AID;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MessageFactory} is the single point that stamps the protocol envelope
 * ({@code ontology=port_command_v1}, {@code language=json}) onto every message,
 * for every performative.
 */
class MessageFactoryTest {

    @Test
    void createStampsEnvelopeAndPerformative() {
        ACLMessage m = MessageFactory.create(ACLMessage.REQUEST);
        assertEquals(ACLMessage.REQUEST, m.getPerformative(), "performative preserved");
        assertEquals("port_command_v1", m.getOntology(), "ontology stamped");
        assertEquals("json", m.getLanguage(), "language stamped");
    }

    // The 10 FIPA performatives the project must exercise (CLAUDE.md success criteria) —
    // matches PerformativeColours' COLOURS map / PerformativeColoursTest's own CANONICAL_TEN,
    // not an independently-invented list (this fixture had drifted: AGREE/FAILURE/NOT_UNDERSTOOD
    // are never actually sent anywhere in the codebase; CANCEL/CONFIRM/DISCONFIRM are).
    @ParameterizedTest(name = "performative {0} carries the envelope")
    @ValueSource(ints = {
            ACLMessage.REQUEST, ACLMessage.PROPOSE, ACLMessage.ACCEPT_PROPOSAL,
            ACLMessage.REJECT_PROPOSAL, ACLMessage.CFP, ACLMessage.CONFIRM, ACLMessage.INFORM,
            ACLMessage.REFUSE, ACLMessage.CANCEL, ACLMessage.DISCONFIRM
    })
    void everyPerformativeCarriesTheEnvelope(int performative) {
        ACLMessage m = MessageFactory.create(performative);
        assertEquals(performative, m.getPerformative());
        assertEquals("port_command_v1", m.getOntology());
        assertEquals("json", m.getLanguage());
    }

    @Test
    void replyStampsEnvelopeAndPreservesConversation() {
        ACLMessage original = MessageFactory.create(ACLMessage.REQUEST);
        original.setSender(new AID("hm", AID.ISGUID)); // ISGUID: no running platform to resolve a local name
        original.setConversationId("conv-42");

        ACLMessage reply = MessageFactory.reply(original, ACLMessage.CONFIRM);

        assertEquals(ACLMessage.CONFIRM, reply.getPerformative(), "reply performative set");
        assertEquals("port_command_v1", reply.getOntology(), "envelope re-stamped on reply");
        assertEquals("json", reply.getLanguage());
        assertEquals("conv-42", reply.getConversationId(), "conversation preserved via createReply()");
    }
}

