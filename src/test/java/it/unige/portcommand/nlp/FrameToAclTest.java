package it.unige.portcommand.nlp;

import java.util.Map;
import java.util.stream.Stream;

import jade.core.AID;
import jade.lang.acl.ACLMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameToAclTest {

    @ParameterizedTest(name = "frameName={0} -> performative={1}")
    @MethodSource("moveTypeCases")
    void mapsEachDcgMoveTypeToItsPerformative(String moveType, int expectedPerformative) {
        Frame frame = new Frame(moveType, Map.of("k", "v"));

        ACLMessage msg = FrameToAcl.build(frame, null, null);

        assertEquals(expectedPerformative, msg.getPerformative(),
                () -> "expected " + ACLMessage.getPerformative(expectedPerformative)
                        + " got " + ACLMessage.getPerformative(msg.getPerformative()));
    }

    static Stream<Arguments> moveTypeCases() {
        return Stream.of(
                Arguments.of("propose", ACLMessage.PROPOSE),
                Arguments.of("counter", ACLMessage.PROPOSE),
                Arguments.of("accept", ACLMessage.ACCEPT_PROPOSAL),
                Arguments.of("reject", ACLMessage.REJECT_PROPOSAL),
                Arguments.of("ask", ACLMessage.QUERY_REF));
    }

    @Test
    void unknownMoveTypeThrows() {
        Frame frame = new Frame("bogus", Map.of());
        assertThrows(IllegalArgumentException.class, () -> FrameToAcl.build(frame, null, null));
    }

    @Test
    void stampsMessageFactoryEnvelope() {
        Frame frame = new Frame("propose", Map.of("price", 2000));

        ACLMessage msg = FrameToAcl.build(frame, null, null);

        assertEquals("port_command_v1", msg.getOntology());
        assertEquals("json", msg.getLanguage());
    }

    @Test
    void contentIsJsonOfFrameElements() {
        Frame frame = new Frame("propose", Map.of("berth_id", "berth_3"));

        ACLMessage msg = FrameToAcl.build(frame, null, null);

        assertTrue(msg.getContent().contains("berth_3"), "content should carry frame elements: " + msg.getContent());
    }

    @Test
    void receiverAndConversationIdSetWhenProvided() {
        Frame frame = new Frame("accept", Map.of());
        AID receiver = new AID("assistant_agent", AID.ISGUID); // ISGUID: no running platform to resolve a local name

        ACLMessage msg = FrameToAcl.build(frame, receiver, "conv-7");

        assertEquals("conv-7", msg.getConversationId());
        assertTrue(msg.getAllReceiver().hasNext(), "receiver must be set");
    }

    @Test
    void receiverAndConversationIdLeftUnsetWhenNull() {
        Frame frame = new Frame("accept", Map.of());

        ACLMessage msg = FrameToAcl.build(frame, null, null);

        assertNull(msg.getConversationId());
        assertFalse(msg.getAllReceiver().hasNext(), "receiver must be left unset, not NPE");
    }
}
