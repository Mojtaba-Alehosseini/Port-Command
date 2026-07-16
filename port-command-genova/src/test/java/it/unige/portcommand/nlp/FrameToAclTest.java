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

    /** A commerce_sell frame carrying {@code move}, the shape the real grammar emits. */
    private static Frame commerceSell(String move) {
        return new Frame("commerce_sell", Map.of(Frame.MOVE, move));
    }

    @ParameterizedTest(name = "move={0} -> performative={1}")
    @MethodSource("moveTypeCases")
    void mapsEachDcgMoveTypeToItsPerformative(String moveType, int expectedPerformative) {
        Frame frame = new Frame("commerce_sell", Map.of(Frame.MOVE, moveType, "k", "v"));

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
                Arguments.of("ask", ACLMessage.QUERY_REF),
                // 16-M2 negation block: a constraint on the negotiation -> REQUEST.
                Arguments.of("constrain", ACLMessage.REQUEST));
    }

    /** 16-M2 command fan-out: a quantified imperative ("send two tugs") must reach every resolved
     * receiver, each as its own MessageFactory-stamped REQUEST addressed to exactly one agent. */
    @Test
    void buildAllFansAQuantifiedCommandOutToOneEnvelopePerReceiver() {
        Frame command = new Frame("command", Map.of("action", "send", "quantifier", 2L, "patient", "tug"));
        AID tug1 = new AID("tug_1", AID.ISGUID); // ISGUID: no running platform to resolve a local name
        AID tug2 = new AID("tug_2", AID.ISGUID);

        var messages = FrameToAcl.buildAll(command, java.util.List.of(tug1, tug2), "conv-9");

        assertEquals(2, messages.size(), "one envelope per receiver");
        for (ACLMessage msg : messages) {
            assertEquals(ACLMessage.REQUEST, msg.getPerformative());
            assertEquals("port_command_v1", msg.getOntology(), "each envelope is MessageFactory-stamped");
            assertEquals("conv-9", msg.getConversationId());
            var receivers = msg.getAllReceiver();
            assertTrue(receivers.hasNext(), "each envelope addresses exactly one agent");
            receivers.next();
            assertFalse(receivers.hasNext(), "a single receiver per envelope, not a broadcast");
        }
        assertEquals("tug_1", ((AID) messages.get(0).getAllReceiver().next()).getLocalName());
        assertEquals("tug_2", ((AID) messages.get(1).getAllReceiver().next()).getLocalName());
    }

    @Test
    void buildAllWithNoReceiversIsAnEmptyList() {
        Frame command = new Frame("command", Map.of("action", "hold", "quantifier", "all", "patient", "tanker"));
        assertTrue(FrameToAcl.buildAll(command, java.util.List.of(), "conv-9").isEmpty());
    }

    /** 16-M2's imperative frame: no {@code move}, and REQUEST is the FIPA performative for
     * "I want you to do this". Selected by frame NAME, not by a move element. */
    @Test
    void commandFrameMapsToRequest() {
        Frame frame = new Frame("command", Map.of("action", "hold", "quantifier", "all"));

        ACLMessage msg = FrameToAcl.build(frame, null, null);

        assertEquals(ACLMessage.REQUEST, msg.getPerformative());
    }

    @Test
    void unknownMoveTypeThrows() {
        Frame frame = commerceSell("bogus");
        assertThrows(IllegalArgumentException.class, () -> FrameToAcl.build(frame, null, null));
    }

    /** The grammar always emits {@code move} first on a commerce_sell frame; a frame without one
     * is a decoder/grammar bug and must fail loudly rather than pick an arbitrary performative. */
    @Test
    void commerceSellFrameWithoutAMoveThrows() {
        Frame frame = new Frame("commerce_sell", Map.of("money", 2000));
        assertThrows(IllegalArgumentException.class, () -> FrameToAcl.build(frame, null, null));
    }

    @Test
    void nullFrameNameThrows() {
        Frame frame = new Frame(null, Map.of(Frame.MOVE, "accept"));
        assertThrows(IllegalArgumentException.class, () -> FrameToAcl.build(frame, null, null));
    }

    @Test
    void stampsMessageFactoryEnvelope() {
        Frame frame = new Frame("commerce_sell", Map.of(Frame.MOVE, "propose", "price", 2000));

        ACLMessage msg = FrameToAcl.build(frame, null, null);

        assertEquals("port_command_v1", msg.getOntology());
        assertEquals("json", msg.getLanguage());
    }

    @Test
    void contentIsJsonOfFrameElements() {
        Frame frame = new Frame("commerce_sell", Map.of(Frame.MOVE, "propose", "berth", "berth_3"));

        ACLMessage msg = FrameToAcl.build(frame, null, null);

        assertTrue(msg.getContent().contains("berth_3"), "content should carry frame elements: " + msg.getContent());
    }

    @Test
    void receiverAndConversationIdSetWhenProvided() {
        Frame frame = commerceSell("accept");
        AID receiver = new AID("assistant_agent", AID.ISGUID); // ISGUID: no running platform to resolve a local name

        ACLMessage msg = FrameToAcl.build(frame, receiver, "conv-7");

        assertEquals("conv-7", msg.getConversationId());
        assertTrue(msg.getAllReceiver().hasNext(), "receiver must be set");
    }

    @Test
    void receiverAndConversationIdLeftUnsetWhenNull() {
        Frame frame = commerceSell("accept");

        ACLMessage msg = FrameToAcl.build(frame, null, null);

        assertNull(msg.getConversationId());
        assertFalse(msg.getAllReceiver().hasNext(), "receiver must be left unset, not NPE");
    }
}
